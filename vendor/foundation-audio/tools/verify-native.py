"""Verify Android JNI ABI parity, separate FFmpeg dependencies and LGPL provenance."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--ndk", required=True)
parser.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
args = parser.parse_args()
root = Path(args.root)
host = "windows-x86_64" if __import__("os").name == "nt" else "linux-x86_64"
readelf = Path(args.ndk) / "toolchains/llvm/prebuilt" / host / "bin/llvm-readelf"
if host.startswith("windows"):
    readelf = readelf.with_suffix(".exe")
expected = set()
for path in (root / "crates/jni-bridge/src").glob("*.rs"):
    expected.update(re.findall(r"Java_com_mocharealm_foundation_audio_\w+", path.read_text(encoding="utf-8")))

def inspect(*arguments):
    return subprocess.check_output([str(readelf), *map(str, arguments)], text=True)

report = {}
for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
    native = root / "jniLibs" / abi
    bridge = native / "libjni_bridge.so"
    dynamic = inspect("--dynamic", bridge)
    symbols = inspect("--dyn-syms", "--wide", bridge)
    actual = set(re.findall(r"Java_com_mocharealm_foundation_audio_\w+", symbols))
    assert not expected - actual, (abi, "Missing JNI exports", sorted(expected - actual))
    needed = re.findall(r"\(NEEDED\).*?\[(.+?)\]", dynamic)
    for name in ("avformat", "avcodec", "swresample", "avutil"):
        assert f"lib{name}.so" in needed, (abi, "FFmpeg is not separately linked", name)
    assert re.search(r"\bUND\s+avformat_open_input\b", symbols), (abi, "FFmpeg implementation embedded")
    hashes = {}
    for library in native.glob("*.so"):
        loads = [line for line in inspect("--program-headers", "--wide", library).splitlines() if line.strip().startswith("LOAD ")]
        assert loads and all(int(line.split()[-1], 16) >= 16384 for line in loads), (abi, "16KiB page alignment", library.name)
        for dependency in re.findall(r"\(NEEDED\).*?\[(.+?)\]", inspect("--dynamic", library)):
            assert (native / dependency).is_file() or dependency in {"libc.so", "libm.so", "libdl.so", "libz.so", "liblog.so", "libandroid.so"}, (abi, library.name, "Unpackaged dependency", dependency)
        hashes[library.name] = hashlib.sha256(library.read_bytes()).hexdigest()
    provenance = root / "ffmpeg-android-shared" / abi / "licenses"
    config = (provenance / f"config-{abi}.mak").read_text()
    for flag in ("GPL", "NONFREE", "VERSION3", "STATIC"):
        assert re.search(rf"^!CONFIG_{flag}=yes$", config, re.M), (abi, "Disallowed FFmpeg configuration", flag)
    assert re.search(r"^CONFIG_SHARED=yes$", config, re.M)
    assert not (provenance / "changes.diff").read_text().strip()
    report[abi] = {"jni_exports": len(actual), "needed": needed, "sha256": hashes,
                   "ffmpeg_commit": (provenance / "source-commit.txt").read_text().strip()}
output = root / "build/native-verification.json"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(f"Verified {len(expected)} original JNI exports, dynamic FFmpeg, LGPL build flags and 16KiB ELF alignment on all 3 ABIs.")
print(output)
