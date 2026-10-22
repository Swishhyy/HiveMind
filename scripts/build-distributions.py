#!/usr/bin/env python3
"""Build portable Java 17 programs without resolving Minecraft/Gradle dependencies."""
import os, shutil, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
os.chdir(ROOT)
JAVA=Path('src/main/java/hivemind/utils')
def run(*args):
    if args[0] in ("javac","jar") and not shutil.which(args[0]):
        args=("java","-m",{"javac":"jdk.compiler/com.sun.tools.javac.Main","jar":"jdk.jartool/sun.tools.jar.Main"}[args[0]],*args[1:])
    subprocess.run([str(x) for x in args],check=True)
def compile_to(output,sources):
    if output.exists():shutil.rmtree(output)
    output.mkdir(parents=True)
    # javac argument files avoid Windows command-line limits.
    listing=output.parent/(output.name+'.sources')
    listing.write_text('\n'.join('"'+str(path).replace('\\','/')+'"' for path in sources)+'\n')
    run('javac','--release','17','--add-modules','jdk.httpserver','-encoding','UTF-8','-d',output,'@'+str(listing))
def helper():
    out=Path('build/helper-classes')
    compile_to(out,[JAVA/'update/UpdateManifest.java',JAVA/'update/UpdateInstaller.java'])
    jar=Path('build/generated/update/assets/hivemind/update-helper.bin');jar.parent.mkdir(parents=True,exist_ok=True)
    run('jar','--create','--file',jar,'--main-class','hivemind.utils.update.UpdateInstaller','-C',out,'.')
def controller():
    out=Path('build/controller-classes')
    sources=list((JAVA/'network').glob('*.java'))+[JAVA/'bot/AtomicProperties.java',JAVA/'bot/StorageRule.java',JAVA/'bot/TrustedControl.java',JAVA/'mining/WorldRoutes.java']+list(Path('controller/src/main/java').rglob('*.java'))
    compile_to(out,sources)
    shutil.copytree('src/main/resources/assets',out/'assets',dirs_exist_ok=True)
    for name in ('LICENSE','COPYING','COPYING.LESSER'):
        if Path(name).exists():shutil.copy(name,out/name)
    jar=Path('build/distributions/hivemind-controller.jar');jar.parent.mkdir(parents=True,exist_ok=True)
    run('jar','--create','--file',jar,'--main-class','hivemind.controller.Main','-C',out,'.')
if __name__=='__main__':
    mode=sys.argv[1] if len(sys.argv)>1 else 'all'
    if mode in ('helper','all'):helper()
    if mode in ('controller','all'):controller()
    if mode not in ('helper','controller','all'):raise SystemExit('Use helper, controller or all')
