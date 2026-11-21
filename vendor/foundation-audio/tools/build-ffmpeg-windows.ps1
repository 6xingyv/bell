#requires -Version 7.0
param(
    [string]$MsysRoot = 'C:/Environments/Tools/msys2',
    [string]$Zig = $env:ZIG_EXE,
    [int]$Jobs = 8
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$version = (Get-Content -LiteralPath "$repo/.zig-version" -Raw).Trim()
if (-not $Zig) { $Zig = "$env:LOCALAPPDATA/Programs/Zig/$version/zig-x86_64-windows-$version/zig.exe" }
if ((& $Zig version).Trim() -ne $version) { throw "Expected Zig $version" }
foreach ($tool in @('usr/bin/bash.exe','usr/bin/make.exe','ucrt64/bin/ar.exe','ucrt64/bin/windres.exe')) {
    if (-not (Test-Path -LiteralPath "$MsysRoot/$tool")) { throw "Missing $tool. In MSYS2 install: make diffutils pkgconf mingw-w64-ucrt-x86_64-binutils" }
}
$sourceRepo = "$repo/third_party/ffmpeg"
$commit = (& git -C $sourceRepo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') { throw 'FFmpeg submodule is missing' }
$source = "$repo/build/ffmpeg-source-$commit"
$build = "$repo/build/ffmpeg-windows"
$prefix = "$repo/ffmpeg-windows-shared"
New-Item -ItemType Directory -Force -Path $source,$build,$prefix | Out-Null
if (-not (Test-Path -LiteralPath "$source/configure")) {
    & git -C $sourceRepo archive --format=tar "--output=$build/source.tar" $commit
    if ($LASTEXITCODE -ne 0) { throw 'git archive failed' }
    & tar -xf "$build/source.tar" -C $source
    if ($LASTEXITCODE -ne 0) { throw 'source extraction failed' }
}
$config = Get-Content -LiteralPath "$PSScriptRoot/ffmpeg-audio-config.json" -Raw | ConvertFrom-Json
$flags = @($config.flags) + @($config.decoders | ForEach-Object { "--enable-decoder=$_" })
[IO.File]::WriteAllText("$build/configure-flags.txt", (($flags -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
$script = @'
set -euo pipefail
export PATH="/ucrt64/bin:/usr/bin:$PATH"
source_dir=$(cygpath -u "$1")
build_dir=$(cygpath -u "$2")
prefix=$(cygpath -u "$3")
export FOUNDATION_ZIG=$(cygpath -u "$4")
export PATH="/ucrt64/bin:/usr/bin:$PATH"
cd "$build_dir"
printf '#!/usr/bin/bash\nexec "$FOUNDATION_ZIG" cc -target x86_64-windows-gnu "$@"\n' > zig-cc
chmod +x zig-cc
zig_windows=$(cygpath -m "$FOUNDATION_ZIG")
printf '#!/usr/bin/bash\nexec /ucrt64/bin/windres --preprocessor="%s cc -target x86_64-windows-gnu -E -xc -DRC_INVOKED" "$@"\n' "$zig_windows" > zig-windres
chmod +x zig-windres
mapfile -t flags < configure-flags.txt
"$source_dir/configure" "${flags[@]}" \
    --target-os=mingw32 --arch=x86_64 --cpu=generic --enable-cross-compile \
    --cc="$build_dir/zig-cc" --host-cc="$build_dir/zig-cc" \
    --ar=/ucrt64/bin/ar --nm=/ucrt64/bin/nm --ranlib=/ucrt64/bin/ranlib \
    --strip=/ucrt64/bin/strip --windres="$build_dir/zig-windres" \
    --disable-zlib --prefix="$prefix"
make -j"$5"
make install
if grep -Eq '^CONFIG_(GPL|NONFREE|VERSION3|STATIC)=yes' ffbuild/config.mak; then
    echo 'Refusing GPL/nonfree/version3/static FFmpeg' >&2
    exit 1
fi
mkdir -p "$prefix/licenses"
cp "$source_dir/COPYING.LGPLv2.1" "$source_dir/LICENSE.md" "$prefix/licenses/"
cp ffbuild/config.mak "$prefix/licenses/config-windows-x86_64.mak"
cp configure-flags.txt "$prefix/licenses/configure-windows-x86_64.txt"
'@
[IO.File]::WriteAllText("$build/build.sh", $script.Replace("`r`n","`n"), [Text.UTF8Encoding]::new($false))
& "$MsysRoot/usr/bin/bash.exe" "$build/build.sh" $source $build $prefix $Zig $Jobs
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg Windows shared build failed' }
[IO.File]::WriteAllText("$prefix/licenses/source-commit.txt", "$commit`n")
[IO.File]::WriteAllText("$prefix/licenses/changes.diff", '')
Write-Host "LGPL shared FFmpeg installed at $prefix"
