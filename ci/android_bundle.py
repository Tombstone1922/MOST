"""Package one universal APK; only the permanent MOST signer may produce OTA files."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

CERTIFICATE = "3c6f68986b7a4fdc964f8c6f33ce0b6cb20102c784ddbeac29884605b040a877"
PACKAGE = "net.h1cloud.most"
out = Path("out")
out.mkdir(exist_ok=True)
build_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0"
apks = list(Path("V2rayNG/app/build/outputs/apk/playstore/debug").glob("*_universal.apk"))
if len(apks) != 1:
    raise SystemExit("Expected exactly one universal Play Store APK")
apk = apks[0]
badging = subprocess.check_output([str(build_tools / "aapt2"), "dump", "badging", str(apk)], text=True)
match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
if not match or match[1] != PACKAGE:
    raise SystemExit("Unexpected APK identity")
version_code, version = int(match[2]), match[3]
if not re.fullmatch(r"\d+\.\d+\.\d+", version):
    raise SystemExit("Use a stable x.y.z versionName for OTA releases")
key = os.environ.get("MOST_SIGNING_KEY_BASE64", "").strip()
password = os.environ.get("MOST_SIGNING_PASSWORD", "").rstrip("\r\n")
os.environ["MOST_SIGNING_PASSWORD"] = password
if bool(key) != bool(password):
    raise SystemExit("Configure both MOST_SIGNING_KEY_BASE64 and MOST_SIGNING_PASSWORD")
if not key:
    shutil.copyfile(apk, out / f"MOST-{version}-Debug.apk")
    with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
        stream.write(f"signed=false\nversion={version}\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
        stream.write("## MOST signing setup required\nBuild succeeded. The artifact uses a debug certificate and is not an OTA update.\n\nRun `ci/Setup-OTA.ps1` once with the private MOST signing-key archive. Then rerun this workflow.\n")
    raise SystemExit(0)
target = out / f"MOST-{version}-Android.apk"
with tempfile.TemporaryDirectory(dir=os.environ.get("RUNNER_TEMP")) as temp:
    key_path = Path(temp) / "most.p12"
    key_path.write_bytes(base64.b64decode(key, validate=True))
    key_path.chmod(0o600)
    subprocess.run([str(build_tools / "apksigner"), "sign", "--ks", str(key_path),
                    "--ks-key-alias", "most", "--ks-pass", "env:MOST_SIGNING_PASSWORD",
                    "--out", str(target), str(apk)], check=True)
verification = subprocess.check_output([str(build_tools / "apksigner"), "verify", "--print-certs", str(target)], text=True)
if f"certificate SHA-256 digest: {CERTIFICATE}" not in verification:
    target.unlink(missing_ok=True)
    raise SystemExit("The signing certificate is not the permanent MOST certificate; OTA publication stopped")
metadata = {
    "schemaVersion": 1, "packageName": PACKAGE, "versionName": version, "versionCode": version_code,
    "sha256": hashlib.file_digest(target.open("rb"), "sha256").hexdigest(),
    "sizeBytes": target.stat().st_size,
    "notes": Path(f"ci/release-notes-{version}.md").read_text() if Path(f"ci/release-notes-{version}.md").exists() else f"MOST {version}",
}
(out / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
    stream.write(f"signed=true\nversion={version}\n")
