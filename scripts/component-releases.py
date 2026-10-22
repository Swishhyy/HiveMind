#!/usr/bin/env python3
"""Independent release identities, plus input checks that prevent silent changes under an old version."""
import ast, hashlib, json, re, subprocess, urllib.request
from pathlib import Path
VERSION_KEYS={'mod_version','player_version','controller_version','release_version','previous_release'}
ROLES=('bot','player','controller')
def properties(text):
    return dict(line.split('=',1) for line in text.splitlines() if '=' in line and not line.startswith(('#','//')))
def versions():
    p=properties(Path('gradle.properties').read_text())
    result={'bot':p['mod_version'],'player':p['player_version'],'controller':p['controller_version'],'release':p['release_version'],'previous':p['previous_release']}
    if any(not re.fullmatch(r'[0-9]{1,6}(\.[0-9]{1,6}){3}',v) for v in result.values()):raise ValueError('Expected four-part component versions')
    return result

def relevant(role,path):
    if path in ('LICENSE','COPYING','COPYING.LESSER'):return True
    if role=='controller':
        return path.startswith(('controller/src/main/','src/main/java/hivemind/utils/network/','src/main/resources/assets/')) or path in ('src/main/java/hivemind/utils/bot/AtomicProperties.java','src/main/java/hivemind/utils/bot/StorageRule.java','src/main/java/hivemind/utils/bot/TrustedControl.java','src/main/java/hivemind/utils/mining/WorldRoutes.java')
    return path.startswith(('src/api/','src/main/','src/launch/','fabric/','buildSrc/','gradle/')) or path in ('build.gradle','settings.gradle','gradlew','gradlew.bat')

def fingerprint(role,ref):
    tree=subprocess.check_output(['git','ls-tree','-r','--full-tree',ref],text=True)
    rows=[]
    # Only the builder functions used by this component count; editing the controller builder
    # must not force fresh Minecraft JARs when the Java 17 game-update helper is unchanged.
    builder=ast.parse(subprocess.check_output(['git','show',ref+':scripts/build-distributions.py'],text=True))
    functions={'run','compile_to','controller' if role=='controller' else 'helper'}
    rows.extend(ast.dump(node,include_attributes=False) for node in builder.body if isinstance(node,(ast.FunctionDef,ast.AsyncFunctionDef)) and node.name in functions)
    for line in tree.splitlines():
        meta,path=line.split('\t',1)
        if relevant(role,path):rows.append(path+' '+meta)
    # Version/release bookkeeping does not change a program; Java/MC/dependency options do.
    raw=subprocess.check_output(['git','show',ref+':gradle.properties'],text=True)
    p=properties(raw)
    if role!='controller':rows.extend(k+'='+v for k,v in sorted(p.items()) if k not in VERSION_KEYS)
    return hashlib.sha256('\n'.join(rows).encode()).hexdigest()

def decide(version,old_version,changed):
    new=tuple(map(int,version.split('.')));old=tuple(map(int,old_version.split('.')))
    if new<old:raise ValueError('Component versions cannot go backwards')
    if changed and new==old:raise ValueError('Component inputs changed; bump its own version')
    if not changed and new!=old:raise ValueError('Component inputs did not change; keep its existing version')
    return new==old

def plan():
    v=versions();previous='v'+v['previous']
    p=properties(subprocess.check_output(['git','show',previous+':gradle.properties'],text=True))
    old={'bot':p['mod_version'],'player':p.get('player_version',p['mod_version']),'controller':p.get('controller_version',p['mod_version'])}
    components={}
    for role in ROLES:
        current=fingerprint(role,'HEAD');before=fingerprint(role,previous)
        try:reuse=decide(v[role],old[role],current!=before)
        except ValueError as e:raise ValueError(role+': '+str(e)) from e
        components[role]={'version':v[role],'inputs':current,'reuse':reuse}
    return {'release':v['release'],'previous':v['previous'],'components':components}

def download(previous,name,destination):
    url=f'https://github.com/Swishhyy/HiveMind/releases/download/v{previous}/{name}'
    with urllib.request.urlopen(url,timeout=60) as r:data=r.read(100*1024*1024+1)
    if len(data)>100*1024*1024:raise ValueError('Release asset oversized')
    destination.write_bytes(data)

def reuse_windows(previous,version,out):
    name=f'hivemind-controller-{version}-windows-x64.zip'
    with urllib.request.urlopen(f'https://github.com/Swishhyy/HiveMind/releases/download/v{previous}/SHA256SUMS.txt',timeout=30) as r:
        sums=dict((line.split()[1],line.split()[0]) for line in r.read(16384).decode().splitlines())
    download(previous,name,out/name)
    if hashlib.sha256((out/name).read_bytes()).hexdigest()!=sums.get(name):raise ValueError('Reused Windows controller checksum mismatch')

def reuse_client(previous,role,version,out):
    name=f'hivemind-{version}.jar' if role=='bot' else f'hivemind-player-{version}.jar'
    manifest='hivemind-26.3-update.properties' if role=='bot' else 'hivemind-player-26.3-update.properties'
    download(previous,manifest,out/manifest);p=properties((out/manifest).read_text())
    if p.get('version')!=version or p.get('distribution')!=role:raise ValueError('Reused component identity mismatch')
    download(previous,name,out/name)
    if hashlib.sha256((out/name).read_bytes()).hexdigest()!=p['sha256'] or (out/name).stat().st_size!=int(p['size']):raise ValueError('Reused component checksum mismatch')

if __name__=='__main__':print(json.dumps(plan(),indent=2))
