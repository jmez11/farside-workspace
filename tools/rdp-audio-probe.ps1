param([int]$Seconds = 20)
$ErrorActionPreference = "Stop"

# Run in the Windows RDP session: quiet test tone plus a temporary capture request.
# Captured microphone audio stays in memory and is discarded when the device closes.
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class QuestAudioProbe {
    [DllImport("winmm.dll", CharSet = CharSet.Unicode)]
    public static extern uint mciSendString(string command, System.Text.StringBuilder result, int length, IntPtr callback);
}
'@
$toneStream = [System.IO.MemoryStream]::new()
$toneWriter = [System.IO.BinaryWriter]::new($toneStream)
$sampleRate = 48000
$dataBytes = $sampleRate * 2
$toneWriter.Write([System.Text.Encoding]::ASCII.GetBytes('RIFF'))
$toneWriter.Write([int](36 + $dataBytes))
$toneWriter.Write([System.Text.Encoding]::ASCII.GetBytes('WAVEfmt '))
$toneWriter.Write([int]16)
$toneWriter.Write([int16]1)
$toneWriter.Write([int16]1)
$toneWriter.Write([int]$sampleRate)
$toneWriter.Write([int]($sampleRate * 2))
$toneWriter.Write([int16]2)
$toneWriter.Write([int16]16)
$toneWriter.Write([System.Text.Encoding]::ASCII.GetBytes('data'))
$toneWriter.Write([int]$dataBytes)
for ($sample = 0; $sample -lt $sampleRate; $sample++) {
    $toneWriter.Write([int16](1500 * [Math]::Sin(2 * [Math]::PI * 440 * $sample / $sampleRate)))
}
$toneWriter.Flush()
$toneStream.Position = 0
$tonePlayer = [System.Media.SoundPlayer]::new($toneStream)
try {
    $tonePlayer.PlayLooping()
    Start-Sleep -Seconds 3
    $openResult = [QuestAudioProbe]::mciSendString('open new type waveaudio alias questAudioMic', $null, 0, [IntPtr]::Zero)
    if ($openResult -ne 0) { throw "Remote microphone open failed: $openResult" }
    [void][QuestAudioProbe]::mciSendString('set questAudioMic bitspersample 16 samplespersec 48000 channels 1 bytespersec 96000 alignment 2', $null, 0, [IntPtr]::Zero)
    $recordResult = [QuestAudioProbe]::mciSendString('record questAudioMic', $null, 0, [IntPtr]::Zero)
    Write-Output "Microphone record result: $recordResult (0 = success)"
    Start-Sleep -Seconds $Seconds
} finally {
    $tonePlayer.Stop()
    $tonePlayer.Dispose()
    [void][QuestAudioProbe]::mciSendString('stop questAudioMic', $null, 0, [IntPtr]::Zero)
    [void][QuestAudioProbe]::mciSendString('close questAudioMic', $null, 0, [IntPtr]::Zero)
    $toneWriter.Dispose()
    $toneStream.Dispose()
}
