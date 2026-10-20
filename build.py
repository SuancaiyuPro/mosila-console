#!/usr/bin/env python3
"""Standalone Android build using an installed SDK and JDK 17+, no third-party runtime dependencies."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent

def run(*args):
    print('>', ' '.join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), check=True, cwd=ROOT)

def build():
    p = argparse.ArgumentParser()
    p.add_argument('--android-jar', type=Path)
    p.add_argument('--build-tools', type=Path)
    p.add_argument('--output', type=Path, default=ROOT/'build'/'MOSILA-v0.3.4.apk')
    args = p.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home()/'Android'/'Sdk'))))
    jar = (args.android_jar or sdk/'platforms'/'android-35'/'android.jar').resolve()
    if args.build_tools:
        tools = args.build_tools.resolve()
    else:
        candidates = sorted((sdk/'build-tools').glob('*'), reverse=True)
        if not candidates:
            raise SystemExit('Install Android SDK Build Tools or pass --build-tools')
        tools = candidates[0]
    java = shutil.which('java')
    if not java or not jar.is_file():
        raise SystemExit('Java and Android SDK platform 35 are required')
    build = ROOT/'build'
    for directory in ['classes','generated','dex','test-classes']:
        d=build/directory
        if d.exists(): shutil.rmtree(d)
        d.mkdir(parents=True)
    src=ROOT/'app'/'src'/'main'
    # aapt2 compile can retain old ZIP entries after a resource is removed.
    (build/'resources.zip').unlink(missing_ok=True)
    run(tools/'aapt2','compile','--dir',src/'res','-o',build/'resources.zip')
    ET.register_namespace('android','http://schemas.android.com/apk/res/android')
    manifest=ET.parse(src/'AndroidManifest.xml')
    manifest.getroot().set('package','com.suancaiyu.mosilalight')
    manifest.write(build/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
    run(tools/'aapt2','link','-o',build/'resources.apk','-I',jar,'--manifest',build/'AndroidManifest.xml','--java',build/'generated','-A',src/'assets',build/'resources.zip')
    sources=list((src/'java').rglob('*.java'))+list((build/'generated').rglob('*.java'))
    run(java,'com.sun.tools.javac.Main','--release','8','-encoding','UTF-8','-classpath',jar,'-d',build/'classes',*sources)
    run(java,'com.sun.tools.javac.Main','--release','8','-classpath',build/'classes','-d',build/'test-classes',ROOT/'tests'/'ProtocolTest.java')
    run(java,'-Xverify:all','-classpath',os.pathsep.join(map(str,[build/'classes',build/'test-classes'])),'ProtocolTest')
    with zipfile.ZipFile(build/'classes.jar','w',zipfile.ZIP_DEFLATED) as z:
        for f in (build/'classes').rglob('*.class'):z.write(f,f.relative_to(build/'classes').as_posix())
    run(java,'-cp',tools/'lib'/'d8.jar','com.android.tools.r8.D8','--release','--min-api','26','--lib',jar,'--output',build/'dex',build/'classes.jar')
    shutil.copyfile(build/'resources.apk',build/'unsigned.apk')
    with zipfile.ZipFile(build/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
        for f in (build/'dex').glob('*.dex'):z.write(f,f.name)
    run(tools/'zipalign','-f','4',build/'unsigned.apk',build/'aligned.apk')
    key=ROOT/'test-signing.p12'
    if not key.is_file():
        keytool=shutil.which('keytool')
        if not keytool:raise SystemExit('keytool required to create the test key')
        run(keytool,'-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass','android','-keypass','android','-alias','mosila-light-test','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=MOSILA Light Test Build')
    out=args.output.resolve();out.parent.mkdir(parents=True,exist_ok=True)
    signer=tools/'lib'/'apksigner.jar'
    run(java,'-jar',signer,'sign','--ks',key,'--ks-pass','pass:android','--key-pass','pass:android','--ks-key-alias','mosila-light-test','--out',out,build/'aligned.apk')
    run(java,'-jar',signer,'verify','--verbose',out)
    print('Built:',out)

if __name__=='__main__': build()
