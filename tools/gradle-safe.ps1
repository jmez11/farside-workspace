$ErrorActionPreference = 'Stop'

# JDK 17's Windows selector wakeup pipe can fail when Gradle inherits the
# user's redirected/short-name temp path. Keep this workaround process-local.
$buildTemp = 'C:\Temp'
New-Item -ItemType Directory -Force -Path $buildTemp | Out-Null
$env:TEMP = $buildTemp
$env:TMP = $buildTemp

& (Join-Path $PSScriptRoot '..\gradlew.bat') @args
exit $LASTEXITCODE
