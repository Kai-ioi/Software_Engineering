param([int]$Port=5173,[string]$BackendUrl='http://127.0.0.1:8080')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskCache=Join-Path $taskRoot '.cache/npm'
$taskOldUrl=$env:BACKEND_URL
try {
  $env:BACKEND_URL=$BackendUrl
  Push-Location (Join-Path $taskRoot 'frontend')
  if(-not(Test-Path 'node_modules')){
    & npm.cmd ci --cache $taskCache
    if($LASTEXITCODE -ne 0){throw 'Frontend dependency installation failed.'}
  }
  & npm.cmd run dev -- --port $Port
  if($LASTEXITCODE -ne 0){throw 'Frontend startup failed.'}
} finally {Pop-Location; $env:BACKEND_URL=$taskOldUrl}
