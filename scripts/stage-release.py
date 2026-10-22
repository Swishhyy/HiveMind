#!/usr/bin/env python3
"""Assemble immutable public Fabric 26.3 update metadata from the actual built jar."""
import hashlib, os, re, shutil, zipfile
from pathlib import Path
version=re.search(r'^mod_version=(.+)$',Path('gradle.properties').read_text(),re.M)[1]
if not re.fullmatch(r'[0-9]{1,6}(\.[0-9]{1,6}){3}',version):raise SystemExit('Expected a four-part release version')
commit=os.environ['GITHUB_SHA']
jar=Path(f'fabric/build/libs/hivemind-{version}.jar')
with zipfile.ZipFile(jar) as archive:
    info=dict(line.split('=',1) for line in archive.read('hivemind-build.properties').decode().splitlines() if '=' in line)
    if info!={'application':'hivemind','version':version,'minecraft':'26.3','loader':'fabric','commit':commit}:raise SystemExit('Jar build identity differs from the release')
    if not any(name.startswith('hivemind/') and name.endswith('.class') for name in archive.namelist()) or any(name.startswith('baritone/') for name in archive.namelist()):raise SystemExit('Jar namespace is not HiveMind')
    if 'assets/hivemind/update-helper.bin' not in archive.namelist():raise SystemExit('Update helper not bundled')
out=Path('build/release');out.mkdir(parents=True,exist_ok=True)
shutil.copy(jar,out/jar.name);shutil.copy('build/distributions/hivemind-controller.jar',out/'hivemind-controller.jar')
checksum=hashlib.sha256(jar.read_bytes()).hexdigest()
(out/'hivemind-26.3-update.properties').write_text(f'application=hivemind\nversion={version}\nminecraft=26.3\nloader=fabric\ncommit={commit}\nsize={jar.stat().st_size}\nsha256={checksum}\nurl=https://github.com/Swishhyy/HiveMind/releases/download/v{version}/{jar.name}\n')
