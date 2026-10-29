$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
try {
  Push-Location (Join-Path $taskRoot 'frontend')
  & npm.cmd ci --cache (Join-Path $taskRoot '.cache/npm')
  if($LASTEXITCODE -ne 0){throw 'npm ci failed'}
  & npm.cmd run build
  if($LASTEXITCODE -ne 0){throw 'Frontend build failed'}
} finally {Pop-Location}
try {
  Push-Location (Join-Path $taskRoot 'backend')
  & mvn "-Dmaven.repo.local=$(Join-Path $taskRoot '.cache/m2')" verify
  if($LASTEXITCODE -ne 0){throw 'Backend verification failed'}
} finally {Pop-Location}
