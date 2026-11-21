"""Inspect the actual FFmpeg DLL license/configuration, not just build manifests."""
import ctypes
import hashlib
import json
import os
from pathlib import Path

root = Path(__file__).resolve().parent.parent
prefix = root / "ffmpeg-windows-shared"
directory = os.add_dll_directory(str(prefix / "bin"))
report = {}
for name in ("avutil", "swresample", "avcodec", "avformat"):
    paths = list((prefix / "bin").glob(f"{name}-*.dll"))
    assert len(paths) == 1, (name, paths)
    path = paths[0]
    library = ctypes.CDLL(str(path))
    license_function = getattr(library, f"{name}_license")
    license_function.restype = ctypes.c_char_p
    configuration_function = getattr(library, f"{name}_configuration")
    configuration_function.restype = ctypes.c_char_p
    license_text = license_function().decode()
    configuration = configuration_function().decode()
    assert license_text == "LGPL version 2.1 or later", (name, license_text)
    for option in ("--disable-gpl", "--disable-nonfree", "--disable-version3", "--enable-shared", "--disable-static"):
        assert option in configuration.split(), (name, option)
    report[path.name] = {"license": license_text, "configuration": configuration,
                         "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    print(path.name, license_text)
output = root / "build/desktop-ffmpeg-verification.json"
output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
