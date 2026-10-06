param(
    [string]$Go = 'go',
    [string]$Sdk = $env:ANDROID_SDK_ROOT
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$build = Join-Path $repo 'build/cloudflared'
New-Item -ItemType Directory -Force $build | Out-Null
$archive = Join-Path $build 'source-pinned.zip'
& curl.exe -L --fail --silent --show-error 'https://codeload.github.com/cloudflare/cloudflared/zip/refs/tags/2026.5.1' -o $archive
if ($LASTEXITCODE -ne 0) { throw 'Cloudflared source download failed' }
if ((Get-FileHash $archive -Algorithm SHA256).Hash -ne '4618A12A8C4EA77954C8048E5EAFF0E9B7BCB16711253F84BD03440D6843A07F') { throw 'Cloudflared source checksum mismatch' }
# A separate fresh directory keeps source edits in an existing work tree intact.
$extract = Join-Path $build ('source-' + [Guid]::NewGuid().ToString('N'))
Expand-Archive -LiteralPath $archive -DestinationPath $extract
$source = Join-Path $extract 'cloudflared-2026.5.1'
$output = Join-Path $repo 'app/src/main/jniLibs/arm64-v8a/libcloudflared.so'
New-Item -ItemType Directory -Force (Split-Path $output) | Out-Null
$old = @{}
foreach ($name in @('GOOS','GOARCH','CGO_ENABLED','CC','GOCACHE')) { $old[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location $repo
try {
    & python (Join-Path $PSScriptRoot 'prepare-cloudflared.py') $source
    if ($LASTEXITCODE -ne 0) { throw 'Cloudflared Android adaptations failed' }
    $env:GOOS = 'android'
    $env:GOARCH = 'arm64'
    $env:CGO_ENABLED = '1'
    $env:CC = Join-Path $Sdk 'ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android32-clang.cmd'
    $env:GOCACHE = Join-Path $build 'go-cache'
    Set-Location $source
    & $Go build -mod=vendor -trimpath -buildvcs=false -ldflags '-s -w -X main.Version=2026.5.1-farside -X main.BuildType=android' -o $output ./cmd/cloudflared
    if ($LASTEXITCODE -ne 0) { throw 'Cloudflared Android build failed' }
    Get-FileHash $output -Algorithm SHA256
} finally {
    Pop-Location
    foreach ($name in $old.Keys) { [Environment]::SetEnvironmentVariable($name, $old[$name], 'Process') }
}
