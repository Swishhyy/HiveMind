#!/usr/bin/env python3
"""Package one Fabric build into compatible bot and locked manual-player editions."""
import hashlib, json, os, re, shutil, zipfile
from pathlib import Path

def properties(data):
    return dict(line.split('=',1) for line in data.decode().splitlines() if '=' in line)

def package(source, destination, version, commit, distribution):
    if distribution not in ('bot','player'):raise ValueError('Invalid distribution')
    with zipfile.ZipFile(source) as archive:
        info=properties(archive.read('hivemind-build.properties'))
        expected={'application':'hivemind','version':version,'minecraft':'26.3','loader':'fabric','commit':commit}
        if info!=expected:raise ValueError('Jar build identity differs from the release')
        names=archive.namelist()
        if not any(n.startswith('hivemind/') and n.endswith('.class') for n in names) or any(n.startswith('baritone/') for n in names):raise ValueError('Jar namespace is not HiveMind')
        if 'assets/hivemind/update-helper.bin' not in names:raise ValueError('Update helper not bundled')
        info['distribution']=distribution
        fabric=json.loads(archive.read('fabric.mod.json'))
        if fabric['id']!='hivemind':raise ValueError('Unexpected mod ID')
        # The same ID intentionally makes Fabric reject installing both editions together.
        fabric['name']='HiveMind Player' if distribution=='player' else 'HiveMind'
        fabric['description']='Manual player sharing for the HiveMind network. No autonomous movement, combat or visible ESP.' if distribution=='player' else 'HiveMind autonomous Minecraft client and network controller integration.'
        replacements={'hivemind-build.properties':''.join(k+'='+v+'\n' for k,v in info.items()).encode(),'fabric.mod.json':(json.dumps(fabric,indent=2)+'\n').encode()}
        with zipfile.ZipFile(destination,'w') as output:
            for entry in archive.infolist():output.writestr(entry,replacements.get(entry.filename,archive.read(entry.filename)))
    digest=hashlib.sha256(destination.read_bytes()).hexdigest()
    return ''.join(k+'='+v+'\n' for k,v in info.items())+f'size={destination.stat().st_size}\nsha256={digest}\nurl=https://github.com/Swishhyy/HiveMind/releases/download/v{version}/{destination.name}\n'

def main():
    version=re.search(r'^mod_version=(.+)$',Path('gradle.properties').read_text(),re.M)[1]
    if not re.fullmatch(r'[0-9]{1,6}(\.[0-9]{1,6}){3}',version):raise SystemExit('Expected a four-part release version')
    commit=os.environ['GITHUB_SHA'];jar=Path(f'fabric/build/libs/hivemind-{version}.jar')
    out=Path('build/release');out.mkdir(parents=True,exist_ok=True)
    for role in ('bot','player'):
        name=f'hivemind-{version}.jar' if role=='bot' else f'hivemind-player-{version}.jar'
        manifest='hivemind-26.3-update.properties' if role=='bot' else 'hivemind-player-26.3-update.properties'
        (out/manifest).write_text(package(jar,out/name,version,commit,role))
    shutil.copy('build/distributions/hivemind-controller.jar',out/'hivemind-controller.jar')
if __name__=='__main__':main()
