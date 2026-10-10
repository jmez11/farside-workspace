param([string]$OutputDir, [string]$Camera, [string]$Size, [switch]$ExpectDenied, [string]$FfmpegPath)
$ErrorActionPreference = 'Stop'
$report = @{ camera = $Camera; requestedSize = $Size; success = $false }
$destination = $OutputDir
# Stage on the host so redirected-drive PNG writes cannot stall live capture.
$OutputDir = Join-Path ([IO.Path]::GetTempPath()) ('farside-camera-' + [guid]::NewGuid().ToString())
[IO.Directory]::CreateDirectory($OutputDir) | Out-Null
$ffmpeg = $null
function Resolve-Ffmpeg {
    if ($FfmpegPath) {
        if (!(Test-Path -LiteralPath $FfmpegPath -PathType Leaf -ErrorAction SilentlyContinue)) {
            throw "Supplied FFmpeg executable is missing or unreadable: $FfmpegPath"
        }
        # Copy once from the redirected drive; repeated decoder starts stay host-local.
        $localTool = Join-Path $OutputDir 'ffmpeg.exe'
        Copy-Item -LiteralPath $FfmpegPath -Destination $localTool
        return $localTool
    }
    $command = Get-Command ffmpeg.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $paths = ([Environment]::GetEnvironmentVariable('Path','Machine') + ';' +
        [Environment]::GetEnvironmentVariable('Path','User')).Split(';')
    foreach ($path in $paths) {
        if (!$path) { continue }
        $candidate = Join-Path ([Environment]::ExpandEnvironmentVariables($path.Trim('"'))) 'ffmpeg.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf -ErrorAction SilentlyContinue) { return $candidate }
    }
    throw 'FFmpeg not found; supply cameraFfmpeg or stage camera-tools/ffmpeg.exe on Quest'
}
function Invoke-Probe([string]$Name, [string]$Arguments) {
    $start = New-Object System.Diagnostics.ProcessStartInfo
    if (!$ffmpeg) { throw 'FFmpeg not found in PATH or existing host installation directories' }
    $start.FileName = $ffmpeg
    $start.Arguments = $Arguments
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardError = $true
    $start.RedirectStandardOutput = $true
    $process = [System.Diagnostics.Process]::Start($start)
    $stderr = $process.StandardError.ReadToEndAsync()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    try {
        if (!$process.WaitForExit(90000)) {
            $process.Kill()
            $process.WaitForExit()
            throw "FFmpeg $Name timed out"
        }
        return $process.ExitCode
    } finally {
        [IO.File]::WriteAllText((Join-Path $OutputDir "$Name.log"), $stderr.GetAwaiter().GetResult() + $stdout.GetAwaiter().GetResult())
        $process.Dispose()
    }
}
try {
    $ffmpeg = Resolve-Ffmpeg
    $report.ffmpeg = $ffmpeg
    # Listing commands intentionally exit nonzero after reporting DirectShow devices/modes.
    $null = Invoke-Probe 'devices' '-hide_banner -list_devices true -f dshow -i dummy'
    $deviceLog = Get-Content (Join-Path $OutputDir 'devices.log') -Raw
    $match = [regex]::Match($deviceLog, '"(' + [regex]::Escape($Camera) + '[^"]*)"\s+\(video\)')
    if (!$match.Success) { throw "Redirected camera '$Camera' absent from FFmpeg device list" }
    $device = $match.Groups[1].Value
    $report.device = $device
    $null = Invoke-Probe 'modes' ('-hide_banner -list_options true -f dshow -i video="' + $device + '"')
    if ($ExpectDenied) {
        $report.attempts = @()
        foreach ($attempt in 1..2) {
            $timer = [Diagnostics.Stopwatch]::StartNew()
            $code = Invoke-Probe "denied-$attempt" ('-hide_banner -f dshow -video_size ' + $Size + ' -i video="' + $device + '" -frames:v 1 -f null -')
            $report.attempts += @{ exitCode = $code; elapsedMs = $timer.ElapsedMilliseconds }
            # DirectShow can report I/O failure while FFmpeg still exits zero with an empty output.
            $deniedLog = Get-Content (Join-Path $OutputDir "denied-$attempt.log") -Raw
            if ($timer.ElapsedMilliseconds -gt 40000 -or $deniedLog -notmatch 'I/O error|Could not run|Could not open' -or
                $deniedLog -match 'frame=\s*[1-9]') { throw "Denied stream did not fail promptly without frames (attempt $attempt)" }
        }
        $report.denied = $true
        $report.success = $true
        return
    }
    # DirectShow samples may contain padded rows. Preserve packets before decoding;
    # otherwise rawvideo can interpret padding as visible pixels and scramble a good image.
    $sample = Join-Path $OutputDir 'sample.yuy2'
    $sampleCode = Invoke-Probe 'sample-layout' ('-hide_banner -y -f dshow -pixel_format yuyv422 -video_size ' + $Size + ' -i video="' + $device + '" -frames:v 1 -c:v copy -f rawvideo "' + $sample + '"')
    if ($sampleCode -ne 0) { throw "Cannot inspect DirectShow sample layout" }
    $dimensions = $Size.Split('x')
    $sampleBytes = (Get-Item $sample).Length
    if ($sampleBytes % [int]$dimensions[1] -ne 0) { throw "DirectShow sample has an unexpected plane layout" }
    $stride = $sampleBytes / [int]$dimensions[1]
    if ($stride -lt [int]$dimensions[0]*2) { throw "DirectShow sample stride is too small" }
    $report.sampleBytes = $sampleBytes
    $report.rowStride = $stride
    # Leave a bounded interval between the sample and sustained capture probes.
    Start-Sleep -Seconds 2
    # Drop packets without decoding or changing bytes: one native sample per 30 frames.
    $raw = Join-Path $OutputDir 'capture.yuy2'
    [IO.File]::WriteAllText((Join-Path $destination 'capture-started.json'),
        (@{ utc = [DateTime]::UtcNow.ToString('o'); camera = $Camera } | ConvertTo-Json))
    $captureCode = Invoke-Probe 'capture-packets' ('-hide_banner -dump -y -f dshow -pixel_format yuyv422 -video_size ' + $Size + ' -i video="' + $device + '" -t 60 -c:v copy -bsf:v "noise=amount=0:drop=''mod(n,30)''" -f rawvideo "' + $raw + '"')
    if ($captureCode -ne 0) { throw "DirectShow packet capture failed" }
    $packetSizes = [regex]::Matches((Get-Content (Join-Path $OutputDir 'capture-packets.log') -Raw), '(?m)^\s*size=(\d+)\s*$')
    if ($packetSizes.Count -eq 0 -or @($packetSizes | Where-Object { [long]$_.Groups[1].Value -ne $sampleBytes }).Count -ne 0) {
        throw "DirectShow changed sample layout during capture"
    }
    $images = Join-Path $OutputDir 'frame-%03d.png'
    $exitCode = Invoke-Probe 'capture' ('-hide_banner -y -f rawvideo -pixel_format yuyv422 -video_size ' + $Size + ' -stride ' + $stride + ' -framerate 1 -i "' + $raw + '" -threads 1 "' + $images + '"')
    $frames = @(Get-ChildItem $OutputDir -Filter 'frame-*.png' | Sort-Object Name)
    if ($exitCode -ne 0 -or $frames.Count -lt 55) { throw "FFmpeg sustained capture failed: exit=$exitCode frames=$($frames.Count)" }
    # Read decoded PNG dimensions, without a scaling filter or relying on negotiated metadata.
    foreach ($frame in $frames) {
        $png = [IO.File]::ReadAllBytes($frame.FullName)
        $width = $png[16]*16777216 + $png[17]*65536 + $png[18]*256 + $png[19]
        $height = $png[20]*16777216 + $png[21]*65536 + $png[22]*256 + $png[23]
        $report.actualSize = "${width}x${height}"
        if ($report.actualSize -ne $Size) { throw "Decoded frame size $($report.actualSize) differs from $Size" }
    }
    $report.frames = $frames.Count
    Remove-Item -LiteralPath $raw, $sample -Force
    $encoded = Join-Path $destination 'encoder.h264'
    if (Test-Path $encoded) {
        $null = Invoke-Probe 'encoder-decode' ('-hide_banner -y -i "' + $encoded + '" -frames:v 1 -threads 1 "' + (Join-Path $OutputDir 'encoder-first.png') + '"')
    }
    $report.success = $true
} catch {
    $report.error = $_.Exception.Message
} finally {
    $scratchTool = Join-Path $OutputDir 'ffmpeg.exe'
    if (Test-Path -LiteralPath $scratchTool) { Remove-Item -LiteralPath $scratchTool }
    # Publish the result last; its arrival means all images/logs are available.
    Get-ChildItem -LiteralPath $OutputDir -File | Copy-Item -Destination $destination
    $temporaryResult = Join-Path $destination 'result.tmp'
    [IO.File]::WriteAllText($temporaryResult, ($report | ConvertTo-Json))
    Move-Item -LiteralPath $temporaryResult -Destination (Join-Path $destination 'result.json') -Force
    $resolvedProbe = [IO.Path]::GetFullPath($OutputDir)
    $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolvedProbe.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $resolvedProbe -Leaf).StartsWith('farside-camera-')) {
        Remove-Item -LiteralPath $resolvedProbe -Recurse -Force
    }
}
