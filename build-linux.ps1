$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$outputDir = Join-Path $projectDir 'dist-linux'

New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
docker build --file (Join-Path $projectDir 'Dockerfile.linux') `
    --output ("type=local,dest=" + $outputDir) $projectDir

Write-Host "Linux application created at: $outputDir"
Write-Host "Copy the complete directory to Linux, then run: ./bin/waveform-viewer"
