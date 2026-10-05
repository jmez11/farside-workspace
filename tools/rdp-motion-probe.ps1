param([int]$Seconds = 35)

# Run on the Windows RDP host during the Quest instrumentation test.
# Creates a temporary moving window; closes itself without touching user documents.
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$probeForm = New-Object System.Windows.Forms.Form
$probeForm.Text = 'RDP motion verification (closes automatically)'
$probeForm.Size = New-Object System.Drawing.Size(480, 300)
$probeForm.StartPosition = 'Manual'
$probeForm.TopMost = $true
$probeForm.BackColor = [System.Drawing.Color]::RoyalBlue
$probeLabel = New-Object System.Windows.Forms.Label
$probeLabel.Dock = 'Fill'
$probeLabel.ForeColor = [System.Drawing.Color]::White
$probeLabel.Font = New-Object System.Drawing.Font('Segoe UI', 22)
$probeLabel.TextAlign = 'MiddleCenter'
$probeForm.Controls.Add($probeLabel)
$probeClock = [System.Diagnostics.Stopwatch]::StartNew()
$probeTimer = New-Object System.Windows.Forms.Timer
$probeTimer.Interval = 16
$probeTimer.Add_Tick({
    $secondsElapsed = $probeClock.Elapsed.TotalSeconds
    if ($secondsElapsed -ge $Seconds) {
        $probeForm.Close()
        return
    }
    $area = [System.Windows.Forms.Screen]::FromControl($probeForm).WorkingArea
    $x = $area.Left + [Math]::Max(0, $area.Width - $probeForm.Width) * (0.5 + 0.35 * [Math]::Sin($secondsElapsed * 2))
    $y = $area.Top + [Math]::Max(0, $area.Height - $probeForm.Height) * (0.5 + 0.3 * [Math]::Cos($secondsElapsed * 2))
    $probeForm.Location = New-Object System.Drawing.Point([int]$x, [int]$y)
    $probeLabel.Text = "RDP motion verification`n$([Math]::Round($secondsElapsed, 2)) seconds"
})
try {
    $probeTimer.Start()
    [System.Windows.Forms.Application]::Run($probeForm)
} finally {
    $probeTimer.Stop()
    $probeTimer.Dispose()
    $probeLabel.Font.Dispose()
    $probeForm.Dispose()
}
