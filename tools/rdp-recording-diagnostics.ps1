param([string]$QuestSerial)
# Read-only host diagnosis by default. Optional QuestSerial copies verified capability evidence
# to this debug app's private files; it does not change Windows settings.
$ErrorActionPreference = 'Stop'
if (-not ('QuestRecordingDiagnostics' -as [type])) {
    Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class QuestRecordingDiagnostics {
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    public struct WaveInCaps {
        public ushort manufacturer, product;
        public uint version;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string name;
        public uint formats;
        public ushort channels, reserved;
    }
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    public struct WaveOutCaps {
        public ushort manufacturer, product;
        public uint version;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string name;
        public uint formats;
        public ushort channels, reserved;
        public uint support;
    }
    [DllImport("slc.dll", CharSet=CharSet.Unicode)]
    public static extern int SLGetWindowsInformationDWORD(string name, out uint value);
    [DllImport("winmm.dll")] public static extern uint waveInGetNumDevs();
    [DllImport("winmm.dll")] public static extern uint waveOutGetNumDevs();
    [DllImport("winmm.dll", CharSet=CharSet.Unicode)]
    public static extern uint waveInGetDevCaps(UIntPtr id, out WaveInCaps caps, uint size);
    [DllImport("winmm.dll", CharSet=CharSet.Unicode)]
    public static extern uint waveOutGetDevCaps(UIntPtr id, out WaveOutCaps caps, uint size);
    [DllImport("wtsapi32.dll", CharSet=CharSet.Unicode)]
    public static extern bool WTSQuerySessionInformation(IntPtr server, int session, int kind, out IntPtr data, out int size);
    [DllImport("wtsapi32.dll")] public static extern void WTSFreeMemory(IntPtr data);
    public static string ClientName(int session) {
        IntPtr data; int size;
        if (!WTSQuerySessionInformation(IntPtr.Zero, session, 10, out data, out size)) return null;
        try { return Marshal.PtrToStringUni(data); } finally { WTSFreeMemory(data); }
    }
}
'@
}
$policyName = 'TerminalServices-DeviceRedirection-Licenses-TSAudioCaptureAllowed'
$policyValue = [uint32]0
$policyResult = [QuestRecordingDiagnostics]::SLGetWindowsInformationDWORD($policyName, [ref]$policyValue)
$windows = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion'
$listener = Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp'
$groupPolicy = Get-ItemProperty 'HKLM:\SOFTWARE\Policies\Microsoft\Windows NT\Terminal Services' -ErrorAction SilentlyContinue
$sessionIdForProbe = (Get-Process -Id $PID).SessionId
$inputDevices = @(
    for ($deviceIndex = 0; $deviceIndex -lt [QuestRecordingDiagnostics]::waveInGetNumDevs(); $deviceIndex++) {
        $caps = [QuestRecordingDiagnostics+WaveInCaps]::new()
        $result = [QuestRecordingDiagnostics]::waveInGetDevCaps([UIntPtr]::new([uint64]$deviceIndex), [ref]$caps,
            [Runtime.InteropServices.Marshal]::SizeOf($caps))
        [pscustomobject]@{ Name = $caps.name; Result = $result }
    }
)
$outputDevices = @(
    for ($deviceIndex = 0; $deviceIndex -lt [QuestRecordingDiagnostics]::waveOutGetNumDevs(); $deviceIndex++) {
        $caps = [QuestRecordingDiagnostics+WaveOutCaps]::new()
        $result = [QuestRecordingDiagnostics]::waveOutGetDevCaps([UIntPtr]::new([uint64]$deviceIndex), [ref]$caps,
            [Runtime.InteropServices.Marshal]::SizeOf($caps))
        [pscustomobject]@{ Name = $caps.name; Result = $result }
    }
)
$report = [pscustomobject]@{
    HostAliases = @($env:COMPUTERNAME; Get-NetIPAddress -AddressFamily IPv4 | Where-Object {
        $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*'
    } | Select-Object -ExpandProperty IPAddress)
    Edition = $windows.EditionID
    Build = "$($windows.CurrentBuild).$($windows.UBR)"
    SessionId = $sessionIdForProbe
    ClientName = [QuestRecordingDiagnostics]::ClientName($sessionIdForProbe)
    TermServiceDll = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Services\TermService\Parameters').ServiceDll
    AudioEnumeratorDll = $listener.AudioEnumeratorDll
    ListenerDisablesCapture = $listener.fDisableAudioCapture
    GroupPolicyDisablesCapture = $groupPolicy.fDisableAudioCapture
    CaptureLicensePolicy = $policyName
    CaptureLicenseResult = '0x{0:X8}' -f $policyResult
    CaptureLicenseAllowed = if ($policyResult -eq 0) { $policyValue } else { $null }
    InputDevices = $inputDevices
    OutputDevices = $outputDevices
    AudioServices = @(Get-Service Audiosrv, AudioEndpointBuilder, UmRdpService | ForEach-Object {
        [pscustomobject]@{ Name = $_.Name; Status = $_.Status.ToString() }
    })
} | ConvertTo-Json -Depth 4

$report
if ($QuestSerial) {
    $reportFile = [IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllText($reportFile, $report, [Text.UTF8Encoding]::new($false))
        # Windows PowerShell treats adb's successful progress on stderr as an error;
        # use adb's exit code to decide whether transfer failed.
        $ErrorActionPreference = 'Continue'
        & adb -s $QuestSerial push $reportFile /data/local/tmp/multidesk-rdp-host-capabilities.json
        if ($LASTEXITCODE -ne 0) { throw 'Could not copy capability evidence to Quest' }
        & adb -s $QuestSerial shell run-as com.farside.workspace cp /data/local/tmp/multidesk-rdp-host-capabilities.json files/rdp-host-capabilities.json
        if ($LASTEXITCODE -ne 0) { throw 'Could not install capability evidence in the debug app' }
    } finally { $ErrorActionPreference = 'Stop'; Remove-Item -LiteralPath $reportFile }
}
