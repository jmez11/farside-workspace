Add-Type -AssemblyName System.Windows.Forms
$root = $PSScriptRoot
$form = New-Object System.Windows.Forms.Form
$form.Text = 'Farside owned keyboard verification'
$form.Width = 800; $form.Height = 450; $form.TopMost = $true
$box = New-Object System.Windows.Forms.TextBox
$box.Multiline = $true; $box.AcceptsReturn = $true; $box.AcceptsTab = $true
$box.Dock = 'Fill'
$form.Controls.Add($box)
# WinForms' native multiline edit control does not implement Ctrl+A consistently.
# Handle the actual modifier/key event explicitly; missing Ctrl still fails receipt.
$box.Add_KeyDown({
    param($sender, $event)
    if ($event.Control -and $event.KeyCode -eq [System.Windows.Forms.Keys]::A) {
        $box.SelectAll(); $event.SuppressKeyPress = $true
    }
})
$utf8 = New-Object System.Text.UTF8Encoding($false)
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 100
$started = [DateTime]::UtcNow
$timer.Add_Tick({
    if ((Test-Path "$root/done.txt") -or ([DateTime]::UtcNow - $started).TotalSeconds -gt 180) {
        $form.Close(); return
    }
    if (Test-Path "$root/request.txt") {
        $id = [IO.File]::ReadAllText("$root/request.txt")
        $json = @{ id=$id; text=$box.Text; focused=$box.Focused } | ConvertTo-Json -Compress
        [IO.File]::WriteAllText("$root/response.json", $json, $utf8)
    }
})
$form.Add_Shown({
    $form.Activate(); $box.Focus()
    [IO.File]::WriteAllText("$root/ready.txt", 'ready', $utf8)
    $timer.Start()
})
try { [System.Windows.Forms.Application]::Run($form) }
finally {
    $timer.Stop(); $timer.Dispose(); $form.Dispose()
    [IO.File]::WriteAllText("$root/stopped.txt", 'stopped', $utf8)
}
