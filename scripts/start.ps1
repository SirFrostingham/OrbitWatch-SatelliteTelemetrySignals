<#
  Start the whole OrbitWatch stack in Docker and wait until it is actually serving data.
    start.cmd                  build (if needed) and start
    start.cmd --observability  also start Prometheus + Grafana
    start.cmd --rebuild        force a rebuild of the service images

  Host ports are checked before anything starts: anything already in use on this machine is
  replaced with the next free port, and the choice is written to .env so docker compose, the
  printed URLs and later runs all agree. Delete .env to go back to the defaults.

  Works on Windows PowerShell 5.1 and PowerShell 7+. Native commands are invoked through
  Invoke-Native below: docker writes ordinary notices to stderr (e.g. "WARNING: No swap limit
  support"), and Windows PowerShell turns native stderr into a terminating NativeCommandError
  when $ErrorActionPreference is Stop. Exit codes are checked explicitly instead.
#>
param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Arguments)

Set-StrictMode -Off
$ErrorActionPreference = 'Continue'      # deliberate: see header
Set-Location (Split-Path -Parent $PSScriptRoot)

# Capture a native command's output (stderr merged in) and its exit code.
function Invoke-Native {
    param([string] $Exe, [string[]] $NativeArgs)
    $out = & $Exe @NativeArgs 2>&1 | ForEach-Object { "$_" }
    [pscustomobject]@{ Output = @($out); Code = $LASTEXITCODE }
}

# Same, but print each line as it arrives - a five-minute image build should not look frozen.
function Invoke-NativeStream {
    param([string] $Exe, [string[]] $NativeArgs)
    & $Exe @NativeArgs 2>&1 | ForEach-Object { Write-Host "$_" }
    return $LASTEXITCODE
}

$profileArgs = @()
$buildArgs   = @('--build')
foreach ($a in $Arguments) {
    switch -regex ($a) {
        '^--?observability$' { $profileArgs = @('--profile', 'observability') }
        '^--?rebuild$'       { $buildArgs   = @('--build', '--no-cache') }
        '^(-h|--help)$'      { Get-Content $PSCommandPath -TotalCount 9 | Select-Object -Skip 1; exit 0 }
        default              { Write-Host "unknown option: $a"; exit 2 }
    }
}

if ((Invoke-Native docker @('info')).Code -ne 0) {
    Write-Host 'Docker is not running - start Docker Desktop and try again.'
    exit 1
}

# ---- host ports --------------------------------------------------------------------------------
$spec = [ordered]@{
    GATEWAY_PORT = 8080; INGEST_PORT = 8081; PROPAGATION_PORT = 8082; ANALYTICS_PORT = 8083
    KAFKA_UI_PORT = 8390; KAFKA_HOST_PORT = 29092; PROMETHEUS_PORT = 9090; GRAFANA_PORT = 3000
}
$envFile = '.env'

# Ports this stack already publishes are not a conflict - they are released as containers are replaced.
$oursNow = @()
foreach ($line in (Invoke-Native docker @('ps', '--format', '{{.Names}} {{.Ports}}')).Output) {
    if ($line -like 'ow-*') {
        foreach ($m in [regex]::Matches($line, ':(\d+)->')) { $oursNow += [int]$m.Groups[1].Value }
    }
}

# One listening-port snapshot is far faster and more reliable than probing each port; fall back to
# a short connect attempt where Get-NetTCPConnection is unavailable (older Windows, non-Windows).
$listening = $null
try { $listening = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Select-Object -ExpandProperty LocalPort -Unique) }
catch { $listening = $null }

function Test-PortBusy([int] $port) {
    if ($oursNow -contains $port) { return $false }
    if ($null -ne $listening) { return ($listening -contains $port) }
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect('127.0.0.1', $port, $null, $null)
        # WaitOne only says the attempt finished, not that it succeeded - EndConnect throws on refused.
        if (-not $iar.AsyncWaitHandle.WaitOne(300)) { return $false }
        $client.EndConnect($iar)
        return $client.Connected
    } catch { return $false } finally { $client.Close() }
}

$fromEnvFile = @{}
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*([A-Z_]+)\s*=\s*(\d+)\s*$') { $fromEnvFile[$Matches[1]] = [int]$Matches[2] }
    }
}

$chosen  = [ordered]@{}
$claimed = @()
$moved   = @()
foreach ($var in $spec.Keys) {
    $preset = [Environment]::GetEnvironmentVariable($var)          # set in this shell -> honour strictly
    if ($preset) {
        if (Test-PortBusy ([int]$preset)) {
            Write-Host "$var=$preset is already in use by something else - pick another and retry."
            exit 1
        }
        $chosen[$var] = [int]$preset; $claimed += [int]$preset; continue
    }
    $want = if ($fromEnvFile.ContainsKey($var)) { $fromEnvFile[$var] } else { $spec[$var] }
    $port = $want
    $guard = 0
    while ((Test-PortBusy $port) -or ($claimed -contains $port)) {
        $port++; $guard++
        if ($guard -gt 60) { Write-Host "could not find a free port near $want for $var"; exit 1 }
    }
    if ($port -ne $want) { $moved += "$var $want -> $port" }
    $chosen[$var] = $port; $claimed += $port
}
foreach ($var in $chosen.Keys) { [Environment]::SetEnvironmentVariable($var, $chosen[$var]) }

# Record the choice so `docker compose` by hand, stop.cmd and the next run all use the same ports.
$keep = @()
if (Test-Path $envFile) {
    $pattern = '^\s*(#\s*ports chosen by|(' + (($spec.Keys) -join '|') + ')\s*=)'
    $keep = @(Get-Content $envFile | Where-Object { $_ -notmatch $pattern })
}
$out = $keep + @("# ports chosen by start.cmd on $(Get-Date -Format 'yyyy-MM-dd HH:mm') - delete this file to reset them")
foreach ($var in $chosen.Keys) { $out += "$var=$($chosen[$var])" }
Set-Content -Path $envFile -Value $out -Encoding ascii

if ($moved.Count -gt 0) {
    Write-Host '==> these default ports are in use on this machine, moved:'
    $moved | ForEach-Object { Write-Host "      $_" }
}

# ---- start -------------------------------------------------------------------------------------
Write-Host '==> building and starting (first run pulls images and builds, ~3-5 min)'
$code = Invoke-NativeStream docker (@('compose') + $profileArgs + @('up', '-d') + $buildArgs)
if ($code -ne 0) { exit 1 }

Write-Host '==> waiting for services to report healthy'
$containers = @('ow-kafka', 'ow-ingest', 'ow-propagation', 'ow-analytics', 'ow-gateway')
$deadline = (Get-Date).AddMinutes(5)
$format = '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}'
while ($true) {
    $pending = @()
    foreach ($c in $containers) {
        $r = Invoke-Native docker @('inspect', '-f', $format, $c)
        $state = if ($r.Code -ne 0 -or $r.Output.Count -eq 0) { 'missing' } else { ($r.Output[0]).Trim() }
        if ($state -ne 'healthy') { $pending += "$c=$state" }
    }
    if ($pending.Count -eq 0) { break }
    if ((Get-Date) -gt $deadline) {
        Write-Host "!! still not healthy after 5 minutes: $($pending -join ' ')"
        Write-Host '   look at:  docker compose logs --tail=50'
        exit 1
    }
    Write-Host "    waiting: $($pending -join ' ')"
    Start-Sleep -Seconds 5
}

Write-Host ''
Invoke-NativeStream docker @('compose', 'ps') | Out-Null
@"

Dashboard      http://localhost:$($chosen.GATEWAY_PORT)
REST/Swagger   http://localhost:$($chosen.GATEWAY_PORT)/swagger-ui.html
GraphiQL       http://localhost:$($chosen.GATEWAY_PORT)/graphiql
Kafka UI       http://localhost:$($chosen.KAFKA_UI_PORT)
Ingest health  http://localhost:$($chosen.INGEST_PORT)/actuator/health/readiness

Data takes ~30 s after start to appear (ingest -> propagation -> analytics).
If CelesTrak is unreachable from this network, ingest seeds from the snapshots in data\,
and reports "degraded" in its health details - the demo still runs on 16,506 real objects.

Follow the logs:  docker compose logs -f
Stop:             stop.cmd
"@ | Write-Host
exit 0
