param(
  [string]$BaseUrl = "https://ai-interview.xin",
  [string]$Prefix = "load25_0807",
  [ValidateRange(1, 100)]
  [int]$Count = 25,
  [switch]$IncludeMedia,
  [ValidateRange(1, 10)]
  [int]$AnswersPerCandidate = 2,
  [string]$VideoFixtureFile = "",
  [ValidateRange(10, 600)]
  [int]$ReportTimeoutSeconds = 120,
  [switch]$ExperienceOnly,
  [ValidateRange(0, 300)]
  [int]$ThinkTimeMinSeconds = 5,
  [ValidateRange(0, 300)]
  [int]$ThinkTimeMaxSeconds = 20,
  [ValidateRange(60, 7200)]
  [int]$PrepareTimeoutSeconds = 600,
  [int]$JobId = 1,
  [string]$Server = "root@47.99.119.191"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$fixturePath = "performance/k6/fixtures/multi-user-$Count.local.json"
$k6FixturePath = "./fixtures/multi-user-$Count.local.json"
$runTimestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$summaryPath = "performance-results/interview-submit-multi-user-$Count-$runTimestamp.json"
$remoteScript = "/tmp/interview-prepare-$runTimestamp.py"
$remoteFixture = "/tmp/multi-user-$Count-$runTimestamp.local.json"

if (-not $env:PERF_FACTORY_PASSWORD) {
  throw "PERF_FACTORY_PASSWORD is required."
}
if ($Prefix -notmatch "^[A-Za-z0-9_]{1,40}$") {
  throw "Prefix may contain only letters, numbers, and underscores (max 40)."
}
if ($ThinkTimeMaxSeconds -lt $ThinkTimeMinSeconds) {
  throw "ThinkTimeMaxSeconds must be greater than or equal to ThinkTimeMinSeconds."
}

$resolvedAddresses = Resolve-DnsName ai-interview.xin -Type A |
  Where-Object { $_.IPAddress } |
  Select-Object -ExpandProperty IPAddress
if ($resolvedAddresses | Where-Object { $_ -like "198.18.*" -or $_ -like "198.19.*" }) {
  throw "Proxy Fake-IP detected. Disable proxy, VPN, and TUN, then flush DNS."
}

$k6Executable = Get-Command k6 -ErrorAction SilentlyContinue
if (-not $k6Executable) {
  $fallbackK6 = "C:\Program Files\k6\k6.exe"
  if (-not (Test-Path -LiteralPath $fallbackK6)) {
    throw "k6 was not found. Install it with winget before running this script."
  }
  $k6Command = $fallbackK6
} else {
  $k6Command = $k6Executable.Source
}

Push-Location $repoRoot
try {
  $passwordBytes = [System.Text.Encoding]::UTF8.GetBytes($env:PERF_FACTORY_PASSWORD)
  $passwordBase64 = [Convert]::ToBase64String($passwordBytes)
  try {
    & scp -q performance/scripts/prepare_multi_user_fixtures.py "${Server}:$remoteScript"
    if ($LASTEXITCODE -ne 0) {
      throw "Failed to copy the fixture preparation script to $Server."
    }

    $remoteCommand = "PERF_FACTORY_PASSWORD_BASE64='$passwordBase64' " +
      "python3 '$remoteScript' --base-url 'http://127.0.0.1:8080' " +
      "--count $Count --prefix '$Prefix' --job-id $JobId " +
      "--prepare-timeout $PrepareTimeoutSeconds --output '$remoteFixture'"
    & ssh $Server $remoteCommand
    if ($LASTEXITCODE -ne 0) {
      throw "Remote fixture preparation failed. k6 was not started."
    }

    & scp -q "${Server}:$remoteFixture" $fixturePath
    if ($LASTEXITCODE -ne 0) {
      throw "Failed to download the prepared fixture from $Server."
    }
  } finally {
    & ssh $Server "rm -f '$remoteScript' '$remoteFixture'" 2>$null
  }

  if (-not (Test-Path -LiteralPath $fixturePath)) {
    throw "Fixture preparation finished without creating $fixturePath."
  }

  $env:PERF_BASE_URL = $BaseUrl
  $env:PERF_ALLOW_WRITES = "true"
  $env:PERF_FIXTURE_FILE = $k6FixturePath
  $env:PERF_VUS = "$Count"
  $env:PERF_DURATION = "3m"
  $env:PERF_RUN_ID = "multi-user-$runTimestamp"
  $env:PERF_INCLUDE_MEDIA = if ($IncludeMedia) { "true" } else { "false" }
  $env:PERF_ANSWERS_PER_CANDIDATE = "$AnswersPerCandidate"
  $env:PERF_REPORT_TIMEOUT_SECONDS = "$ReportTimeoutSeconds"
  $env:PERF_COMPLETE_INTERVIEW = if ($ExperienceOnly) { "false" } else { "true" }
  $env:PERF_WAIT_FOR_REPORT = if ($ExperienceOnly) { "false" } else { "true" }
  $env:PERF_THINK_TIME_MIN_SECONDS = "$ThinkTimeMinSeconds"
  $env:PERF_THINK_TIME_MAX_SECONDS = "$ThinkTimeMaxSeconds"
  if ($VideoFixtureFile) {
    $resolvedVideoFixture = Resolve-Path -LiteralPath $VideoFixtureFile
    $env:PERF_VIDEO_FIXTURE_FILE = $resolvedVideoFixture.Path.Replace('\', '/')
  } else {
    Remove-Item Env:PERF_VIDEO_FIXTURE_FILE -ErrorAction SilentlyContinue
  }

  & $k6Command run `
    --summary-export=$summaryPath `
    performance/k6/interview-submit-multi-user.js
  if ($LASTEXITCODE -ne 0) {
    throw "k6 failed with exit code $LASTEXITCODE. Check its threshold and request output."
  }

  Write-Host "Performance report saved to $summaryPath"
} finally {
  Pop-Location
}
