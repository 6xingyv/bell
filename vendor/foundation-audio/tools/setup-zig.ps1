#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$version = (Get-Content -LiteralPath "$PSScriptRoot/../.zig-version" -Raw).Trim()
if ($version -ne '0.17.0') { throw 'Update the pinned archive checksum when changing .zig-version.' }
$parent = "$env:LOCALAPPDATA/Programs/Zig/$version"
$compiler = "$parent/zig-x86_64-windows-$version/zig.exe"
if (Test-Path -LiteralPath $compiler) {
    if ((& $compiler version).Trim() -ne $version) { throw 'Installed compiler version does not match' }
    Write-Host $compiler
    exit 0
}
$archive = Join-Path ([IO.Path]::GetTempPath()) "zig-x86_64-windows-$version-verified.zip"
$checksum = 'b5663f69581dcf391293fbf16c06cb80d81d806545ce618b4d0bab7f0eb8c428'
Invoke-WebRequest "https://ziglang.org/download/$version/zig-x86_64-windows-$version.zip" -OutFile $archive
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $checksum) { throw 'Zig archive checksum mismatch' }
New-Item -ItemType Directory -Force -Path $parent | Out-Null
Expand-Archive -LiteralPath $archive -DestinationPath $parent
if ((& $compiler version).Trim() -ne $version) { throw 'Extracted compiler version does not match' }
Write-Host $compiler
