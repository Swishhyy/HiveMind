#!/usr/bin/env python3
"""Publish only a fully built main-branch version; existing releases stay immutable."""
import hashlib, os, re, subprocess
from pathlib import Path
version=re.search(r'^mod_version=(.+)$',Path('gradle.properties').read_text(),re.M)[1]
if not re.fullmatch(r'[0-9]{1,6}(\.[0-9]{1,6}){3}',version):raise SystemExit('Invalid release version')
tag='v'+version
if subprocess.run(['gh','release','view',tag,'--repo','Swishhyy/HiveMind'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
    print('Version already released. Bump mod_version for another release.');raise SystemExit(0)
root=Path('release-assets');assets=sorted(root.iterdir())
expected={f'hivemind-{version}.jar',f'hivemind-player-{version}.jar','hivemind-player-26.3-update.properties','hivemind-controller.jar','hivemind-26.3-update.properties',f'hivemind-controller-{version}-windows-x64.zip'}
if {path.name for path in assets}!=expected:raise SystemExit('Release assets are incomplete')
checksums=root/'SHA256SUMS.txt';checksums.write_text(''.join(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+path.name+'\n' for path in assets));assets.append(checksums)
subprocess.run(['gh','release','create',tag,'--repo','Swishhyy/HiveMind','--target',os.environ['GITHUB_SHA'],'--title','HiveMind '+version,'--notes-file','RELEASE_NOTES.md',*[str(path) for path in assets]],check=True)
