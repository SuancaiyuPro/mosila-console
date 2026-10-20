#!/usr/bin/env python3
"""Prepare the two AndroidX AAR classpaths and run portable Maven tests."""
import argparse
import io
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile

root = Path(__file__).resolve().parent
p = argparse.ArgumentParser()
p.add_argument('--android-jar', type=Path)
p.add_argument('--maven', default='mvn')
args, extra = p.parse_known_args()
sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home()/'Android'/'Sdk'))))
jar = (args.android_jar or sdk/'platforms/android-35/android.jar').resolve()
if not jar.is_file():
    p.error('Install Android SDK platform 35 or pass --android-jar')
aar_dir = root/'aar'
aar_dir.mkdir(exist_ok=True)
for name, artifact in [('monitor', 'androidx/test/monitor/1.7.2/monitor-1.7.2'), ('espresso', 'androidx/test/espresso/espresso-idling-resource/3.6.1/espresso-idling-resource-3.6.1')]:
    target = aar_dir/(name+'.jar')
    if target.is_file():
        continue
    cached = Path.home()/'.m2/repository'/(artifact+'.aar')
    if cached.is_file():
        data = cached.read_bytes()
    else:
        with urllib.request.urlopen('https://dl.google.com/dl/android/maven2/'+artifact+'.aar', timeout=120) as response:
            data = response.read()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        target.write_bytes(archive.read('classes.jar'))
subprocess.run([args.maven, '-B', '-f', str(root/'pom.xml'), '-Dandroid.jar='+str(jar), *extra, 'test'], check=True)
