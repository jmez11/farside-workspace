param([Parameter(Mandatory=$true)][string]$Token)
$ErrorActionPreference = 'Stop'
$output = $PSScriptRoot
$old = Get-Clipboard -Raw
try {
    Set-Content -LiteralPath "$output\ready.txt" -Value ready
    $row = 'Farside large clipboard ' + [char]0x03a9 + ' ' + [char]0x4e2d + " 0123456789`r`n"
    $expected = "Farside $Token`r`n" + $row * 4096
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $text = Get-Clipboard -Raw
        if ($text -ceq $expected) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($text -cne $expected) { throw "Large clipboard mismatch: received $($text.Length), expected $($expected.Length)" }
    $reply = 'REMOTE->QUEST:' + $text
    Set-Clipboard -Value $reply
    @{ok=$true;outboundUnits=$text.Length;inboundUnits=$reply.Length} | ConvertTo-Json -Compress | Set-Content -LiteralPath "$output\result.json"
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while (!(Test-Path -LiteralPath "$output\done.txt") -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 200 }
} catch {
    @{ok=$false;error=$_.Exception.Message} | ConvertTo-Json -Compress | Set-Content -LiteralPath "$output\result.json"
} finally {
    try {
        if (![string]::IsNullOrEmpty($old)) { Set-Clipboard -Value $old }
        else {
            Add-Type -AssemblyName System.Windows.Forms
            [System.Windows.Forms.Clipboard]::Clear()
        }
    } finally {
        Set-Content -LiteralPath "$output\restored.txt" -Value restored
    }
}
