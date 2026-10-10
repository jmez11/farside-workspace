param([string]$OutputDir)
$ErrorActionPreference = 'Stop'
$report = @{ success = $false }
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('farside-browser-' + [guid]::NewGuid())
[IO.Directory]::CreateDirectory($scratch) | Out-Null
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$browser = $null
try {
    $edge = @("${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe", "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (!$edge) { throw 'Microsoft Edge not installed' }
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $page = @'
<!doctype html><meta charset="utf-8"><title>Farside browser A/V sync verification</title>
<style>body{margin:0;background:black}video{width:100vw;height:100vh;object-fit:fill}canvas{display:none}</style>
<video autoplay loop playsinline></video><canvas width="1280" height="720"></canvas>
<script>
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const report={success:false};
 let context;
 try {
  context=new AudioContext(); await context.resume();
  const destination=context.createMediaStreamDestination();
  const oscillator=context.createOscillator(); oscillator.frequency.value=660;
  const gain=context.createGain(); gain.gain.value=0;
  oscillator.connect(gain).connect(destination); oscillator.start();
  const canvas=document.querySelector('canvas'), drawing=canvas.getContext('2d');
  const stream=canvas.captureStream(30); stream.addTrack(destination.stream.getAudioTracks()[0]);
  const chunks=[]; const recorder=new MediaRecorder(stream,{mimeType:'video/webm;codecs=vp8,opus',videoBitsPerSecond:4000000});
  recorder.ondataavailable=e=>chunks.push(e.data);
  const done=new Promise(r=>recorder.onstop=r); recorder.start();
  const origin=performance.now();
  let timer=setInterval(()=>{
   const seconds=(performance.now()-origin)/1000;
   // Unequal intervals make multi-second offsets distinguishable from a one-second alias.
   const flash=[0.1,1.05,2.4,3.05,5.05].some(t=>seconds%6>=t&&seconds%6<t+0.18);
   gain.gain.setValueAtTime(flash?0.06:0,context.currentTime);
   drawing.fillStyle=flash?'white':'#102030'; drawing.fillRect(0,0,1280,720);
   if(!flash){drawing.fillStyle='#506070';drawing.fillRect((seconds*240)%1200,0,80,720);}
  },1000/30);
  await sleep(6000); clearInterval(timer); gain.gain.value=0; recorder.stop(); await done;
  oscillator.stop(); await context.close(); context=null;
  const video=document.querySelector('video'); video.src=URL.createObjectURL(new Blob(chunks,{type:recorder.mimeType}));
  await video.play(); let frames=0;
  const count=()=>{frames++;video.requestVideoFrameCallback(count)}; video.requestVideoFrameCallback(count);
  await fetch('/started',{method:'POST',body:JSON.stringify({size:video.videoWidth+'x'+video.videoHeight})});
  await sleep(40000); report.frames=frames; report.success=frames>600; report.actualSize=video.videoWidth+'x'+video.videoHeight;
  video.pause();
 }catch(error){report.error=error.name+': '+error.message;}
 finally{if(context)await context.close();await fetch('/result',{method:'POST',body:JSON.stringify(report)});}
})();
</script>
'@
    $arguments = '--user-data-dir="' + $scratch + '" --start-fullscreen --disable-features=CalculateNativeWinOcclusion --guest --disable-extensions --disable-background-mode --disable-background-timer-throttling --disable-renderer-backgrounding --no-first-run --no-default-browser-check --autoplay-policy=no-user-gesture-required --enable-features=MediaLogToConsole --enable-logging --log-file="' + $scratch + '\edge.log" --v=1 --vmodule=video_capture*=4 http://127.0.0.1:' + $port
    # Actual encoded browser video and audio in a separate, owned guest profile.
    # This interactive verification window must be visible in the remote desktop
    # so the Quest observes the video's rendered flashes, including after reconnect.
    $browser = Start-Process -FilePath $edge -ArgumentList $arguments -WindowStyle Normal -PassThru
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
                [IO.File]::WriteAllText((Join-Path $OutputDir 'video-started.json'), $body)
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
    Get-ChildItem -LiteralPath $scratch -File | Where-Object { $_.Name -match '^browser-frame-.*\.png$|^edge\.log$' } | Copy-Item -Destination $OutputDir
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutputDir 'result.pending.json') -Encoding UTF8
    Move-Item -LiteralPath (Join-Path $OutputDir 'result.pending.json') -Destination (Join-Path $OutputDir 'result.json')
    # The absolute GUID directory is owned by this probe.
    $resolvedScratch = (Resolve-Path -LiteralPath $scratch).ProviderPath
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (!$resolvedScratch.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Scratch cleanup escaped temporary directory' }
    Remove-Item -LiteralPath $resolvedScratch -Recurse -Force -ErrorAction SilentlyContinue
}
