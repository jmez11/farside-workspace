param(
    [string]$Go = 'go',
    [string]$Sdk = $env:ANDROID_SDK_ROOT,
    [string]$Version = '2026.5.1',
    [string]$ExpectedSha256 = '4618A12A8C4EA77954C8048E5EAFF0E9B7BCB16711253F84BD03440D6843A07F',
    [string]$ModMode = 'vendor'
)
$ErrorActionPreference = 'Stop'
if (-not $Sdk) { throw 'Android SDK not found: pass -Sdk or set $env:ANDROID_SDK_ROOT' }
if ($ModMode -notin @('vendor', 'mod')) { throw "ModMode must be 'vendor' or 'mod'" }
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$build = Join-Path $repo 'build/cloudflared'
New-Item -ItemType Directory -Force $build | Out-Null
$archive = Join-Path $build 'source-pinned.zip'
# curl.exe exists on Windows; on other platforms fall back to curl from PATH.
$curl = if (Get-Command 'curl.exe' -ErrorAction SilentlyContinue) { 'curl.exe' } else { 'curl' }
& $curl -L --fail --silent --show-error "https://codeload.github.com/cloudflare/cloudflared/zip/refs/tags/$Version" -o $archive
if ($LASTEXITCODE -ne 0) { throw 'Cloudflared source download failed' }
if ($ExpectedSha256 -and (Get-FileHash $archive -Algorithm SHA256).Hash -ne $ExpectedSha256.ToUpperInvariant()) { throw 'Cloudflared source checksum mismatch' }
# A separate fresh directory keeps source edits in an existing work tree intact.
$extract = Join-Path $build ('source-' + [Guid]::NewGuid().ToString('N'))
Expand-Archive -LiteralPath $archive -DestinationPath $extract
$source = Join-Path $extract "cloudflared-$Version"
$output = Join-Path $repo 'app/src/main/jniLibs/arm64-v8a/libcloudflared.so'
New-Item -ItemType Directory -Force (Split-Path $output) | Out-Null
$old = @{}
foreach ($name in @('GOOS','GOARCH','CGO_ENABLED','CC','GOCACHE')) { $old[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
# python is the norm on Windows; python3 on most other platforms.
$py = if (Get-Command 'python' -ErrorAction SilentlyContinue) { 'python' } else { 'python3' }
# The NDK prebuilt toolchain directory differs by host OS.
$prebuiltBase = Join-Path $Sdk 'ndk/26.1.10909125/toolchains/llvm/prebuilt'
$prebuilt = if (Test-Path (Join-Path $prebuiltBase 'windows-x86_64')) { 'windows-x86_64' } else { 'linux-x86_64' }
$clangName = if ($prebuilt -eq 'windows-x86_64') { 'aarch64-linux-android32-clang.cmd' } else { 'aarch64-linux-android32-clang' }
Push-Location $repo
try {
    & $py (Join-Path $PSScriptRoot 'prepare-cloudflared.py') $source $Version
    if ($LASTEXITCODE -ne 0) { throw 'Cloudflared Android adaptations failed' }
    $env:GOOS = 'android'
    $env:GOARCH = 'arm64'
    $env:CGO_ENABLED = '1'
    $env:CC = Join-Path $prebuiltBase "$prebuilt/bin/$clangName"
    $env:GOCACHE = Join-Path $build 'go-cache'
    Set-Location $source
    & $Go build -mod=$ModMode -trimpath -buildvcs=false -ldflags "-s -w -X main.Version=$Version-farside -X main.BuildType=android" -o $output ./cmd/cloudflared
    if ($LASTEXITCODE -ne 0) { throw 'Cloudflared Android build failed' }
    Get-FileHash $output -Algorithm SHA256
} finally {
    Pop-Location
    foreach ($name in $old.Keys) { [Environment]::SetEnvironmentVariable($name, $old[$name], 'Process') }
}
