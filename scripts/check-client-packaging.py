#!/usr/bin/env python3
"""Exercise the real edition packager, metadata and update manifests without Minecraft dependencies."""
import hashlib, importlib.util, json, tempfile, zipfile
from pathlib import Path
spec=importlib.util.spec_from_file_location('packager',Path(__file__).with_name('stage-release.py'))
packager=importlib.util.module_from_spec(spec);spec.loader.exec_module(packager)
version='0.1.0.7';commit='a'*40
with tempfile.TemporaryDirectory() as directory:
    root=Path(directory);source=root/'common.jar'
    with zipfile.ZipFile(source,'w') as z:
        z.writestr('hivemind-build.properties',f'application=hivemind\nversion={version}\nminecraft=26.3\nloader=fabric\ncommit={commit}\n')
        z.writestr('fabric.mod.json',json.dumps({'id':'hivemind','version':version,'mixins':['mixins.hivemind.json']}))
        z.writestr('hivemind/Example.class',b'fixture');z.writestr('assets/hivemind/update-helper.bin',b'helper');z.writestr('META-INF/jars/native.jar',b'nested dependency')
    for role in ('bot','player'):
        name=f'hivemind-{version}.jar' if role=='bot' else f'hivemind-player-{version}.jar'
        artifact=root/name;manifest=packager.properties(packager.package(source,artifact,version,commit,role).encode())
        assert manifest['distribution']==role and manifest['url'].endswith('/'+name)
        assert manifest['sha256']==hashlib.sha256(artifact.read_bytes()).hexdigest() and int(manifest['size'])==artifact.stat().st_size
        with zipfile.ZipFile(artifact) as z:
            assert len(z.namelist())==len(set(z.namelist()))
            assert packager.properties(z.read('hivemind-build.properties'))['distribution']==role
            fabric=json.loads(z.read('fabric.mod.json'));assert fabric['id']=='hivemind' and fabric['mixins']==['mixins.hivemind.json']
            assert fabric['name']==('HiveMind Player' if role=='player' else 'HiveMind')
            assert z.read('META-INF/jars/native.jar')==b'nested dependency' and z.read('assets/hivemind/update-helper.bin')==b'helper'
    try:packager.package(source,root/'bad.jar',version,'b'*40,'player')
    except ValueError:pass
    else:raise AssertionError('Mismatched release commit accepted')
print('Client packaging checks passed: unchanged bot filename, player edition metadata, manifests/checksums, nested dependencies and common mod-ID collision')
