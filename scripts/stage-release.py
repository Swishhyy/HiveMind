#!/usr/bin/env python3
"""Package one Fabric build into compatible bot and locked manual-player editions."""
import hashlib, json, os, re, shutil, zipfile
from pathlib import Path
import importlib.util
spec=importlib.util.spec_from_file_location("components",Path(__file__).with_name("component-releases.py"))
components=importlib.util.module_from_spec(spec);spec.loader.exec_module(components)

def properties(data):
    return dict(line.split('=',1) for line in data.decode().splitlines() if '=' in line)

def package(source, destination, version, commit, distribution, build_version=None, release_version=None):
    if distribution not in ('bot','player'):raise ValueError('Invalid distribution')
    with zipfile.ZipFile(source) as archive:
        info=properties(archive.read('hivemind-build.properties'))
        expected={'application':'hivemind','version':build_version or version,'minecraft':'26.3','loader':'fabric','commit':commit}
        if info!=expected:raise ValueError('Jar build identity differs from the release')
        names=archive.namelist()
        if not any(n.startswith('hivemind/') and n.endswith('.class') for n in names) or any(n.startswith('baritone/') for n in names):raise ValueError('Jar namespace is not HiveMind')
        if 'assets/hivemind/update-helper.bin' not in names:raise ValueError('Update helper not bundled')
        info['distribution']=distribution
        info['version']=version
        fabric=json.loads(archive.read('fabric.mod.json'))
        if fabric['id']!='hivemind':raise ValueError('Unexpected mod ID')
        # The same ID intentionally makes Fabric reject installing both editions together.
        fabric['version']=version
        fabric['name']='HiveMind Player' if distribution=='player' else 'HiveMind'
        fabric['description']='Manual player sharing for the HiveMind network. No autonomous movement, combat or visible ESP.' if distribution=='player' else 'HiveMind autonomous Minecraft client and network controller integration.'
        replacements={'hivemind-build.properties':''.join(k+'='+v+'\n' for k,v in info.items()).encode(),'fabric.mod.json':(json.dumps(fabric,indent=2)+'\n').encode()}
        with zipfile.ZipFile(destination,'w') as output:
            for entry in archive.infolist():output.writestr(entry,replacements.get(entry.filename,archive.read(entry.filename)))
    digest=hashlib.sha256(destination.read_bytes()).hexdigest()
    return ''.join(k+'='+v+'\n' for k,v in info.items())+f'size={destination.stat().st_size}\nsha256={digest}\nurl=https://github.com/Swishhyy/HiveMind/releases/download/v{release_version or version}/{destination.name}\n'

def main():
    plan=components.plan();v=components.versions();commit=os.environ['GITHUB_SHA'];jar=Path(f"fabric/build/libs/hivemind-{v['bot']}.jar")
    out=Path('build/release');out.mkdir(parents=True,exist_ok=True)
    for role in ('bot','player'):
        if plan['components'][role]['reuse']:components.reuse_client(v['previous'],role,v[role],out);continue
        name=f"hivemind-{v[role]}.jar" if role=='bot' else f"hivemind-player-{v[role]}.jar"
        manifest='hivemind-26.3-update.properties' if role=='bot' else 'hivemind-player-26.3-update.properties'
        (out/manifest).write_text(package(jar,out/name,v[role],commit,role,build_version=v['bot'],release_version=v['release']))
    controller=out/'hivemind-controller.jar';manifest=out/'hivemind-controller-update.properties'
    if plan['components']['controller']['reuse']:
        components.download(v['previous'],controller.name,controller);components.download(v['previous'],manifest.name,manifest)
        p=components.properties(manifest.read_text())
        if p['version']!=v['controller'] or p['sha256']!=hashlib.sha256(controller.read_bytes()).hexdigest() or int(p['size'])!=controller.stat().st_size:raise ValueError('Reused controller identity mismatch')
    else:
        shutil.copy('build/distributions/hivemind-controller.jar',controller)
        with zipfile.ZipFile(controller) as z:p=properties(z.read('hivemind-controller.properties'))
        if p!={'application':'hivemind-controller','java':'17','version':v['controller'],'commit':commit}:raise ValueError('Controller identity mismatch')
        p.update(size=str(controller.stat().st_size),sha256=hashlib.sha256(controller.read_bytes()).hexdigest(),url=f"https://github.com/Swishhyy/HiveMind/releases/download/v{v['release']}/hivemind-controller.jar")
        manifest.write_text(''.join(k+'='+val+'\n' for k,val in p.items()))
    (out/'hivemind-components.json').write_text(json.dumps(plan,indent=2)+'\n')
if __name__=='__main__':main()
