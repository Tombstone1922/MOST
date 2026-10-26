"""Publish a complete draft, including exact source and the APK-derived OTA manifest."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--apk", required=True, type=Path)
parser.add_argument("--metadata", required=True, type=Path)
parser.add_argument("--source-ref", required=True)
parser.add_argument("--bootstrap", action="store_true")
args = parser.parse_args()
repo = os.environ["GITHUB_REPOSITORY"]
metadata = json.loads(args.metadata.read_text())
version = metadata["versionName"]
tag = "v" + version
if metadata["packageName"] != "net.h1cloud.most" or metadata["versionCode"] <= 0:
    raise SystemExit("Invalid MOST package metadata")
if args.apk.stat().st_size != metadata["sizeBytes"] or hashlib.file_digest(args.apk.open("rb"), "sha256").hexdigest() != metadata["sha256"]:
    raise SystemExit("APK hash or size differs from the verified metadata")

def gh(*params, optional=False):
    result = subprocess.run(["gh", *params, "--repo", repo] if params[0] == "release" else ["gh", *params],
                            text=True, capture_output=True)
    if result.returncode and not (optional and "404" in result.stderr):
        raise SystemExit(result.stderr or "GitHub command failed")
    return None if result.returncode else result.stdout

existing_text = gh("api", f"repos/{repo}/releases/tags/{tag}", optional=True)
existing = json.loads(existing_text) if existing_text else None
with tempfile.TemporaryDirectory() as temp_dir:
    temp = Path(temp_dir)
    if existing and not existing["draft"]:
        gh("release", "download", tag, "--pattern", "most-update.json", "--dir", str(temp))
        previous = json.loads((temp / "most-update.json").read_text())
        if previous["sha256"] != metadata["sha256"]:
            raise SystemExit("This version is already published with another APK. Increment versionName and versionCode.")
        print(f"{tag} is already published; immutable assets kept")
        raise SystemExit(0)
    latest_text = gh("api", f"repos/{repo}/releases/latest", optional=True)
    if latest_text:
        latest = json.loads(latest_text)
        latest_dir = temp / "latest"
        latest_dir.mkdir()
        gh("release", "download", latest["tag_name"], "--pattern", "most-update.json", "--dir", str(latest_dir))
        latest_code = json.loads((latest_dir / "most-update.json").read_text())["versionCode"]
        if latest_code >= metadata["versionCode"]:
            if args.bootstrap:
                print("A newer MOST release already exists; bootstrap skipped")
                raise SystemExit(0)
            raise SystemExit("OTA versionCode must be greater than the published versionCode")
    manifest = dict(metadata)
    manifest["apkUrl"] = f"https://github.com/{repo}/releases/download/{tag}/MOST-{version}-Android.apk"
    manifest_path = temp / "most-update.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    apk_path = temp / f"MOST-{version}-Android.apk"
    # Keep the release name stable even when the build artifact used a different filename.
    import shutil
    shutil.copyfile(args.apk, apk_path)
    source_path = temp / f"MOST-{version}-Source.zip"
    roots = subprocess.check_output(["git", "ls-tree", "--name-only", args.source_ref], text=True).splitlines()
    roots = [name for name in roots if name != "release-inputs"]
    subprocess.run(["git", "archive", "--format=zip", "--prefix=MOST/", "-o", str(source_path), args.source_ref, "--", *roots], check=True)
    notes = temp / "notes.md"
    notes.write_text(metadata.get("notes", f"MOST {version}"))
    if not existing:
        gh("release", "create", tag, "--draft", "--target", args.source_ref, "--title", f"MOST {version}", "--notes-file", str(notes))
    gh("release", "upload", tag, str(apk_path), str(source_path), str(manifest_path), "--clobber")
    gh("release", "edit", tag, "--draft=false", "--latest")
    print(f"Published https://github.com/{repo}/releases/tag/{tag}")
