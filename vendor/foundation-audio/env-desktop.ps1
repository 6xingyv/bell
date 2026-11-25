# Desktop (Windows MSVC) build environment for foundation.audio.
# Usage (from audio/):
#   . .\env-desktop.ps1
#   cargo test -p fabric-audio-adapter --features engine-adapter
#   cargo build -p engine

$ErrorActionPreference = "Stop"

$ffmpegDir = if ($env:FFMPEG_DIR) { $env:FFMPEG_DIR } else { "C:\Environments\Tools\ffmpeg" }
if (-not (Test-Path -LiteralPath (Join-Path $ffmpegDir "include"))) {
    throw "FFmpeg headers not found under $ffmpegDir (expected include/ and lib/)"
}

$vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
$vsRoot = if (Test-Path $vswhere) {
    & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
} else {
    "C:\Program Files\Microsoft Visual Studio\2022\Community"
}
if (-not $vsRoot) {
    throw "Visual Studio with MSVC tools not found"
}

$libclangCandidates = @(
    (Join-Path $vsRoot "VC\Tools\Llvm\x64\bin"),
    (Join-Path $vsRoot "VC\Tools\Llvm\bin")
)
$libclang = $libclangCandidates | Where-Object { Test-Path (Join-Path $_ "libclang.dll") } | Select-Object -First 1
if (-not $libclang) {
    throw "libclang.dll not found under $vsRoot\VC\Tools\Llvm"
}

$vcvars = Join-Path $vsRoot "VC\Auxiliary\Build\vcvars64.bat"
if (-not (Test-Path -LiteralPath $vcvars)) {
    throw "vcvars64.bat not found at $vcvars"
}

# Import MSVC linker/include paths into this PowerShell session.
$vcEnv = cmd /c "`"$vcvars`" >nul && set"
foreach ($line in $vcEnv) {
    if ($line -match "^(.*?)=(.*)$") {
        Set-Item -Path "Env:$($matches[1])" -Value $matches[2]
    }
}

$env:FFMPEG_DIR = $ffmpegDir
$env:LIBCLANG_PATH = $libclang
$env:PATH = "$ffmpegDir\bin;$libclang;$env:PATH"

Write-Host "FFMPEG_DIR     = $env:FFMPEG_DIR"
Write-Host "LIBCLANG_PATH  = $env:LIBCLANG_PATH"
Write-Host "VS             = $vsRoot"
Write-Host "Desktop audio env ready." -ForegroundColor Green
