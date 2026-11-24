param(
    [Parameter(Mandatory)] [string] $KtServe,
    [Parameter(Mandatory)] [string] $Axum,
    [Parameter(Mandatory)] [string] $K6,
    [Parameter(Mandatory)] [string] $Output
)

$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'Run the pilot on Windows with PowerShell 7.' }

$executables = @{
    'ktserve-iocp' = (Resolve-Path -LiteralPath $KtServe).Path
    'rust-axum' = (Resolve-Path -LiteralPath $Axum).Path
}
$K6 = (Resolve-Path -LiteralPath $K6).Path
$Output = [System.IO.Path]::GetFullPath($Output)
$available = [System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity.ToInt64()
$cpuBits = @(0..62 | ForEach-Object {
    $bit = 1L -shl $_
    if ($available -band $bit) { $bit }
} | Select-Object -First 4)
if ($cpuBits.Length -lt 4) { throw 'The pilot needs four disjoint logical CPUs.' }
$serverMask = $cpuBits[0] -bor $cpuBits[1]
$loadMask = $cpuBits[2] -bor $cpuBits[3]
$env:TOKIO_WORKER_THREADS = '2'
$env:GOMAXPROCS = '2'
$env:KTSERVE_BACKEND = 'iocp'
$env:BIND_HOST = '127.0.0.1'

New-Item -ItemType Directory -Path $Output | Out-Null
$system = Get-CimInstance Win32_OperatingSystem
$processors = @(Get-CimInstance Win32_Processor)
[ordered]@{
    mode = 'exploratory same host'
    git_sha = (git -C $PSScriptRoot rev-parse HEAD)
    os = $system.Caption
    os_version = $system.Version
    architecture = $env:PROCESSOR_ARCHITECTURE
    cpu = @($processors.Name)
    physical_cores_visible = @($processors.NumberOfCores)
    logical_processors_visible = @($processors.NumberOfLogicalProcessors)
    server_affinity_mask = $serverMask
    load_affinity_mask = $loadMask
    tokio_worker_threads = 2
    k6_gomaxprocs = 2
    k6_version = (& $K6 version)
    binaries = @(Get-FileHash -Algorithm SHA256 -LiteralPath (@($executables.Values) + @($K6)) |
        Select-Object Hash, Path)
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$Output/environment.json"

function Stop-Child($Process) {
    if (-not $Process.HasExited) { $Process.Kill() }
    $Process.WaitForExit()
    $Process.Dispose()
}

function Start-Pinned($Executable, $Arguments, $Mask, $LogPrefix) {
    $options = @{
        FilePath = $Executable
        ArgumentList = $Arguments
        PassThru = $true
        NoNewWindow = $true
        WorkingDirectory = $PSScriptRoot
        RedirectStandardOutput = "$LogPrefix.stdout.log"
        RedirectStandardError = "$LogPrefix.stderr.log"
    }
    $process = Start-Process @options
    try {
        $process.ProcessorAffinity = [IntPtr] $Mask
        $process.Refresh()
        if ($process.ProcessorAffinity.ToInt64() -ne $Mask) { throw "Unexpected affinity for $Executable." }
        return $process
    } catch {
        Stop-Child $process
        throw
    }
}

function Wait-ForServer($Process, $BaseUri) {
    $timeout = [System.Diagnostics.Stopwatch]::StartNew()
    while ($timeout.Elapsed.TotalSeconds -lt 10 -and -not $Process.HasExited) {
        try {
            $response = Invoke-WebRequest -Uri "$BaseUri/health" -TimeoutSec 1 -NoProxy
            if ($response.StatusCode -eq 200 -and $response.Content -ceq 'ok') { return }
        } catch {
            # The server may not yet have bound its listener.
        }
        Start-Sleep -Milliseconds 100
    }
    throw 'The server did not return the expected health response.'
}

function Invoke-Load($BaseUri, $Vus, $LogPrefix) {
    $env:PERFLAB_TARGET = "$BaseUri/json"
    $env:PERFLAB_SUMMARY = "$LogPrefix.json"
    $env:PERFLAB_VUS = "$Vus"
    $load = Start-Pinned $K6 @('run', '--quiet', 'benchmark.js') $loadMask $LogPrefix
    try {
        if (-not $load.WaitForExit(20000)) { throw 'k6 exceeded its time budget.' }
        if ($load.ExitCode -ne 0) { throw "k6 failed with exit code $($load.ExitCode)." }
        return @{
            metrics = (Get-Content -LiteralPath "$LogPrefix.json" -Raw | ConvertFrom-Json).metrics
            cpu_seconds = $load.TotalProcessorTime.TotalSeconds
        }
    } finally {
        Stop-Child $load
    }
}

$variants = @('ktserve-iocp', 'rust-axum')
$invalid = $false
for ($run = 1; $run -le 3; $run++) {
    foreach ($vus in @(1, 16)) {
        $start = ($run - 1 + [int]($vus -eq 16)) % 2
        foreach ($variant in @($variants[$start], $variants[1 - $start])) {
            $prefix = "$Output/$variant-$vus-vus-$run"
            $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
            $listener.Start()
            $port = $listener.LocalEndpoint.Port
            $listener.Stop()
            $env:PORT = "$port"
            $uri = "http://127.0.0.1:$port"
            $server = Start-Pinned $executables[$variant] @("$port", '127.0.0.1') $serverMask "$prefix.server"
            try {
                Wait-ForServer $server $uri
                $warmup = (Invoke-Load $uri $vus "$prefix.warmup").metrics
                $cpuBefore = $server.TotalProcessorTime.TotalSeconds
                $load = Invoke-Load $uri $vus "$prefix.measured"
                $server.Refresh()
                $measured = $load.metrics
                $requests = $measured.http_reqs.values.count
                $failures = $measured.perflab_failed_responses.values.count
                $latency = $measured.perflab_success_duration.values
                $warmupFailures = $warmup.perflab_failed_responses.values.count
                $valid = ($warmup.http_reqs.values.count -gt 0 -and $warmupFailures -eq 0
                    -and $requests -gt 0 -and $failures -eq 0 -and $latency.count -eq $requests)
                $invalid = $invalid -or -not $valid
                [PSCustomObject][ordered]@{
                    variant = $variant
                    endpoint = '/json'
                    protocol = 'HTTP/1.1'
                    vus = $vus
                    run = $run
                    warmup_s = 5
                    duration_s = 5
                    port = $port
                    requests = $requests
                    failures = $failures
                    warmup_failures = $warmupFailures
                    requests_per_second = $measured.http_reqs.values.rate
                    p50_ms = $latency.'p(50)'
                    p95_ms = $latency.'p(95)'
                    p99_ms = $latency.'p(99)'
                    server_cpu_seconds = $server.TotalProcessorTime.TotalSeconds - $cpuBefore
                    peak_working_set_kib = $server.PeakWorkingSet64 / 1KB
                    working_set_kib = $server.WorkingSet64 / 1KB
                    load_cpu_seconds = $load.cpu_seconds
                    valid = [int] $valid
                } | Export-Csv -LiteralPath "$Output/results.csv" -NoTypeInformation -Append
            } finally {
                Stop-Child $server
            }
        }
    }
}
if ($invalid) { throw "The pilot produced invalid trials. See $Output." }
