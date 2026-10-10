param([string]$OutputDir, [string]$Camera, [string]$Size, [switch]$Windowed, [int]$CaptureSeconds = 0, [int]$Attempts = 2)
$ErrorActionPreference = 'Stop'
$report = @{ success = $false; camera = $Camera; requestedSize = $Size }
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('farside-browser-' + [guid]::NewGuid())
[IO.Directory]::CreateDirectory($scratch) | Out-Null
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$browser = $null
try {
    $edge = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe", "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (!$edge) { throw 'Microsoft Edge not installed' }
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $configuration = @{ camera = $Camera; size = $Size; seconds = $CaptureSeconds; attempts = $Attempts } | ConvertTo-Json -Compress
    $page = @'
<!doctype html><meta charset="utf-8"><title>Farside browser camera verification</title>
<video autoplay muted playsinline></video><pre id="status"></pre>
<script>
const config = CONFIGURATION;
const video = document.querySelector('video');
const report = {success:false, camera:config.camera, userAgent:navigator.userAgent, visibility:document.visibilityState, attempts:[]};
const sleep = ms => new Promise(r => setTimeout(r,ms));
(async () => {
  let stream;
  try {
    const devices = await navigator.mediaDevices.enumerateDevices();
    report.devices = devices.map(d=>({kind:d.kind,label:d.label}));
    const device = devices.find(d=>d.kind==='videoinput' && d.label.startsWith(config.camera));
    if (!device) throw new Error('Camera absent or labels inaccessible: '+JSON.stringify(report.devices));
    const [width,height] = config.size.split('x').map(Number);
    for(let attempt=0; attempt<config.attempts; attempt++) {
      const started = performance.now();
      stream = await navigator.mediaDevices.getUserMedia({audio:false,video:{deviceId:{exact:device.deviceId},width:{exact:width},height:{exact:height},frameRate:{ideal:30}}});
      video.srcObject = stream;
      await video.play();
      let frames=0;
      const capturedStream=stream;
      let frameCallback;
      const count=()=>{if(video.srcObject!==capturedStream) return; frames++; frameCallback=video.requestVideoFrameCallback(count);};
      frameCallback=video.requestVideoFrameCallback(count);
      await fetch('/started',{method:'POST',body:JSON.stringify({attempt})});
      const captureStart=performance.now();
      const samples=[];
      const sampleCanvas=document.createElement('canvas'); sampleCanvas.width=8; sampleCanvas.height=8;
      let previousFrames=0;
      const deadline=captureStart+Math.max(30000,config.seconds*1000+5000);
      while((frames<60 || performance.now()-captureStart<config.seconds*1000) && performance.now()<deadline) {
        await sleep(1000);
        sampleCanvas.getContext('2d').drawImage(video,0,0,8,8);
        const pixels=sampleCanvas.getContext('2d').getImageData(0,0,8,8).data;
        let black=true; for(let p=0;p<pixels.length;p+=4) if(pixels[p]>8 || pixels[p+1]>8 || pixels[p+2]>8) black=false;
        samples.push({elapsedMs:performance.now()-captureStart,frames:frames-previousFrames,black}); previousFrames=frames;
      }
      video.cancelVideoFrameCallback(frameCallback);
      const canvas=document.createElement('canvas');
      canvas.width=video.videoWidth; canvas.height=video.videoHeight;
      canvas.getContext('2d').drawImage(video,0,0);
      const actualSize=video.videoWidth+'x'+video.videoHeight;
      report.attempts.push({frames,actualSize,samples,elapsedMs:performance.now()-started,muted:stream.getVideoTracks()[0].muted,readyState:stream.getVideoTracks()[0].readyState,settings:stream.getVideoTracks()[0].getSettings()});
      await fetch('/frame-'+attempt,{method:'POST',body:canvas.toDataURL('image/png')});
      stream.getTracks().forEach(t=>t.stop()); stream=null; video.srcObject=null;
      if(frames<55 || actualSize!==config.size) throw new Error('Missing decoded browser frames: '+JSON.stringify(report.attempts));
      await sleep(2000);
    }
    report.frames=Math.min(...report.attempts.map(a=>a.frames));
    report.actualSize=report.attempts[0].actualSize;
    report.success=true;
  } catch(e) { report.error=e.name+': '+e.message; }
  finally {
    if(stream) stream.getTracks().forEach(t=>t.stop());
    document.querySelector('#status').textContent=JSON.stringify(report,null,2);
    await fetch('/result',{method:'POST',body:JSON.stringify(report)});
  }
})();
</script>
'@
    $page = $page.Replace('CONFIGURATION', $configuration)
    $arguments = '--user-data-dir="' + $scratch + '" --headless=new --guest --disable-extensions --disable-background-mode --disable-background-timer-throttling --disable-renderer-backgrounding --no-first-run --no-default-browser-check --use-fake-ui-for-media-stream --autoplay-policy=no-user-gesture-required --enable-features=MediaLogToConsole --enable-logging --log-file="' + $scratch + '\edge.log" --v=1 --vmodule=video_capture*=4 http://127.0.0.1:' + $port
    if ($Windowed) { $arguments = $arguments.Replace('--headless=new ', '--disable-features=CalculateNativeWinOcclusion ') }
    # Separate browser profile; actual camera devices, automated site permission only.
    $browser = Start-Process -FilePath $edge -ArgumentList $arguments -WindowStyle Hidden -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(110)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (!$listener.Pending()) { Start-Sleep -Milliseconds 100; continue }
        $client = $listener.AcceptTcpClient()
        try {
            $client.ReceiveTimeout = 10000
            $stream = $client.GetStream()
            $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $false, 4096, $true)
            $request = $reader.ReadLine()
            # Chromium opens speculative connections without an HTTP request.
            if (!$request) { continue }
            $length = 0
            while ($line = $reader.ReadLine()) { if ($line -match '^Content-Length: (\d+)') { $length = [int]$Matches[1] } }
            $body = ''
            if ($length) {
                $chars = New-Object char[] $length
                $offset = 0
                while ($offset -lt $length) { $n = $reader.Read($chars,$offset,$length-$offset); if (!$n) { throw 'Truncated browser POST' }; $offset += $n }
                $body = -join $chars
            }
            $response = ''
            if ($request -match '^GET / ') { $response = $page }
            elseif ($request -match '^POST /frame-(\d+) ') {
                [IO.File]::WriteAllBytes((Join-Path $scratch ('browser-frame-' + $Matches[1] + '.png')), [Convert]::FromBase64String($body.Split(',')[1]))
            } elseif ($request -match '^POST /started ') {
                [IO.File]::WriteAllText((Join-Path $OutputDir 'browser-started.json'), $body)
            } elseif ($request -match '^POST /result ') { $report = $body | ConvertFrom-Json }
            $bytes = [Text.Encoding]::UTF8.GetBytes($response)
            $header = [Text.Encoding]::ASCII.GetBytes("HTTP/1.1 200 OK`r`nContent-Type: text/html; charset=utf-8`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n")
            $stream.Write($header,0,$header.Length); $stream.Write($bytes,0,$bytes.Length)
            if ($request -match '^POST /result ') { break }
        } finally { $client.Dispose() }
    }
    if (!$report.error -and !$report.success) { throw 'Browser capture timed out' }
} catch { $report.error = $_.Exception.Message }
finally {
    $listener.Stop()
    # Kill only processes started with this unique test profile.
    Get-CimInstance Win32_Process -Filter "name='msedge.exe'" | Where-Object { $_.CommandLine -like "*$scratch*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    if ($report.success) {
        $captureLog = Get-Content -LiteralPath (Join-Path $scratch 'edge.log') -Raw
        $mf = $captureLog -match ('Open, device.name = ' + [regex]::Escape($Camera) + '.*e5323777-f976-4f5b-9b55-b94699c46e44')
        $report | Add-Member -NotePropertyName backend -NotePropertyValue $(if ($mf) { 'MediaFoundation' } else { 'DirectShow' }) -Force
        if (!$mf) { $report.success = $false; $report | Add-Member -NotePropertyName error -NotePropertyValue 'Browser fell back to DirectShow' -Force }
    }
    Get-ChildItem -LiteralPath $scratch -File | Where-Object { $_.Name -match '^browser-frame-.*\.png$|^edge\.log$' } | Copy-Item -Destination $OutputDir
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutputDir 'result.pending.json') -Encoding UTF8
    Move-Item -LiteralPath (Join-Path $OutputDir 'result.pending.json') -Destination (Join-Path $OutputDir 'result.json')
    # The absolute GUID directory is owned by this probe.
    $resolvedScratch = (Resolve-Path -LiteralPath $scratch).ProviderPath
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (!$resolvedScratch.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Scratch cleanup escaped temporary directory' }
    Remove-Item -LiteralPath $resolvedScratch -Recurse -Force -ErrorAction SilentlyContinue
}
