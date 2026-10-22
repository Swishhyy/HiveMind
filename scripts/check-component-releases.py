#!/usr/bin/env python3
import importlib.util
from pathlib import Path
spec=importlib.util.spec_from_file_location('components',Path(__file__).with_name('component-releases.py'));c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
def rejects(task):
    try:task()
    except ValueError:return
    raise AssertionError('Invalid version transition accepted')
assert c.decide('0.1.0.9','0.1.0.9',False)
assert not c.decide('0.1.0.10','0.1.0.9',True)
rejects(lambda:c.decide('0.1.0.9','0.1.0.9',True))
rejects(lambda:c.decide('0.1.0.10','0.1.0.9',False))
rejects(lambda:c.decide('0.1.0.8','0.1.0.9',True))
assert c.relevant('controller','controller/src/main/java/hivemind/controller/Main.java') and not c.relevant('bot','controller/src/main/java/hivemind/controller/Main.java')
assert c.relevant('bot','src/main/java/hivemind/process/StalkProcess.java') and not c.relevant('controller','src/main/java/hivemind/process/StalkProcess.java')
for role in ('bot','player','controller'):assert c.relevant(role,'src/main/java/hivemind/utils/network/NetworkProtocol.java')
for role in ('bot','player','controller'):assert not c.relevant(role,'README.md') and not c.relevant(role,'src/test/java/Test.java')
print('Component release checks passed: independent versions, unchanged-asset reuse, no downgrades, isolated and shared source inputs')
