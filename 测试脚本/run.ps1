param(
    [ValidatePattern('^(0[1-9]|1[0-8])$')]
    [string]$CaseId
)

$ErrorActionPreference = 'Stop'
$backend = Join-Path (Split-Path -Parent $PSScriptRoot) 'school_system/backend'
$test = 'SystemAcceptanceCasesTest'
if ($CaseId) {
    $test += "#case$CaseId"
}

Push-Location -LiteralPath $backend
try {
    & mvn.cmd "-Dtest=$test" '-Dsurefire.failIfNoSpecifiedTests=true' test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven tests failed (exit $LASTEXITCODE)."
    }
} finally {
    Pop-Location
}
