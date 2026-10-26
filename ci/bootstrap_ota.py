"""Reassemble the previously signed 0.4.0 APK, then publish its matching source."""
import json
from pathlib import Path
import subprocess

root = Path("release-inputs/0.4.0")
metadata = root / "metadata.json"
if not metadata.exists():
    raise SystemExit("Bootstrap assets are missing")
parts = sorted(root.glob("apk.part-*"))
if len(parts) != 41:
    raise SystemExit("Expected all 41 bootstrap parts")
Path("out").mkdir(exist_ok=True)
apk = Path("out/MOST-0.4.0-Android.apk")
with apk.open("wb") as stream:
    for part in parts:
        stream.write(part.read_bytes())
subprocess.run(["python3", "ci/publish_release.py", "--apk", str(apk), "--metadata", str(metadata),
                "--source-ref", "2b587ebdf3dbb396a175125e8cab8fb998dbf272", "--bootstrap"], check=True)
