"""Fetch only version-pinned upstream runtime binaries; no private subscription access."""
import hashlib
import io
import json
from pathlib import Path
import urllib.request
import zipfile

APP = Path('out/windows/app')
CORE = APP / 'core'
CORE.mkdir(parents=True, exist_ok=True)
LICENSES = APP / 'licenses'
LICENSES.mkdir(exist_ok=True)


def archive(url, expected):
    with urllib.request.urlopen(url, timeout=90) as response:
        data = response.read(80 * 1024 * 1024)
    if hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError('Upstream download checksum mismatch')
    return zipfile.ZipFile(io.BytesIO(data))


with archive('https://github.com/XTLS/Xray-core/releases/download/v26.10.21/Xray-windows-64.zip',
             'fc58c39ee2f2717b4c54d28d374362389e5319b20ae44aea76ea45a5243ec827') as z:
    name = next(n for n in z.namelist() if n.lower().endswith('xray.exe'))
    (CORE / 'xray.exe').write_bytes(z.read(name))
    for n in z.namelist():
        if 'license' in Path(n).name.lower():
            (LICENSES / ('Xray-' + Path(n).name)).write_bytes(z.read(n))
with archive('https://www.wintun.net/builds/wintun-0.14.1.zip',
             '07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51') as z:
    name = next(n for n in z.namelist() if n.lower().endswith('/amd64/wintun.dll'))
    (CORE / 'wintun.dll').write_bytes(z.read(name))
    for n in z.namelist():
        if 'license' in Path(n).name.lower():
            (LICENSES / ('Wintun-' + Path(n).name)).write_bytes(z.read(n))
(CORE / 'manifest.json').write_text(json.dumps({p.name: hashlib.sha256(p.read_bytes()).hexdigest()
    for p in (CORE / 'xray.exe', CORE / 'wintun.dll')}, indent=2))
(APP / 'README.txt').write_bytes(Path('Windows/README_RU.txt').read_bytes())
(LICENSES / 'MOST-GPL-3.0.txt').write_bytes(Path('LICENSE').read_bytes())
(LICENSES / 'UPSTREAM.txt').write_text('MOST source: https://github.com/Tombstone1922/MOST\n'
    'Xray 26.10.21 source, MPL-2.0: https://github.com/XTLS/Xray-core/tree/v26.10.21\n'
    'Wintun 0.14.1 signed distribution and license: https://www.wintun.net/\n')
print('Pinned Xray and official signed Wintun prepared; checksums verified.')
