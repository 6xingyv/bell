#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$version = (Get-Content -LiteralPath (Join-Path $repo '.zig-version') -Raw).Trim()
$compiler = $env:ZIG_EXE
if (-not $compiler) {
    $compiler = Join-Path $env:LOCALAPPDATA "Programs\Zig\$version\zig-x86_64-windows-$version\zig.exe"
}
if (-not (Test-Path -LiteralPath $compiler)) {
    throw "Install Zig $version or set ZIG_EXE to that version's zig.exe. See zig/MIGRATION.txt."
}
$actual = & $compiler version
if ($LASTEXITCODE -ne 0 -or $actual.Trim() -ne $version) { throw "Expected Zig $version, found $actual" }
Push-Location $repo
try {
    & $compiler @args
    $result = $LASTEXITCODE
} finally { Pop-Location }
exit $result
