param([ValidateSet('demo','postgres')][string]$Database='demo',[int]$Port=8080,[switch]$SeedDemo)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskBackend=Join-Path $taskRoot 'backend'
$taskRepo=Join-Path $taskRoot '.cache/m2'
if(-not(Get-Command java -ErrorAction SilentlyContinue)){throw 'Java 21 is required.'}
if(-not(Get-Command mvn -ErrorAction SilentlyContinue)){throw 'Maven 3.9+ is required.'}
if($Database -eq 'postgres' -and -not $env:DB_PASSWORD){throw 'Set DB_PASSWORD before starting the postgres profile.'}
$taskProfiles=$Database
if($SeedDemo -and $Database -eq 'postgres'){
  # The rightmost profile must retain the PostgreSQL datasource, while demo enables only the seeder.
  $taskProfiles='demo,postgres'
}
$taskOldPort=$env:SERVER_PORT
try {
  $env:SERVER_PORT="$Port"
  Push-Location $taskBackend
  & mvn "-Dmaven.repo.local=$taskRepo" "-Dspring-boot.run.profiles=$taskProfiles" spring-boot:run
  if($LASTEXITCODE -ne 0){throw 'Backend startup failed. Check the output above.'}
} finally {Pop-Location; $env:SERVER_PORT=$taskOldPort}
