#!/usr/bin/env python3
"""Compile/run the production HDR pipeline; never changes installed instances."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, time

p = argparse.ArgumentParser()
p.add_argument('--project', default=str(Path(__file__).resolve().parents[1]))
p.add_argument('--java', required=True)
p.add_argument('--hdr')
a = p.parse_args()
root = Path(a.project).resolve()
out = root / 'build/hdr-integration'
out.mkdir(parents=True, exist_ok=True)
classes = out / 'classes'
classes.mkdir(exist_ok=True)
cp = (root / 'build/compile-classpath.txt').read_text(encoding='utf-8').strip()
sources = [root / 'src/test/java/dev/yg/skyboxblock/custom' / x for x in ['RadianceHdrRegression.java', 'SkyConfigRegression.java', 'HdrRegistryRegression.java']]
java = Path(a.java)
compile_cp = str(root / 'build/classes/java/main') + os.pathsep + cp
result = {'project': str(root), 'tests': []}
def run(cmd, logfile):
    with logfile.open('wb') as f:
        proc = subprocess.run(cmd, cwd=root, stdout=f, stderr=subprocess.STDOUT)
    print(logfile.read_text(encoding='utf-8', errors='replace'), flush=True)
    if proc.returncode: raise RuntimeError(f'exit {proc.returncode}: {logfile}')
run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-proc:none', '-cp', compile_cp, '-d', str(classes), *map(str,sources)], out/'compile.log')
run_cp = str(classes) + os.pathsep + compile_cp
for cls in ['RadianceHdrRegression','SkyConfigRegression','HdrRegistryRegression']:
    scratch = out / cls
    scratch.mkdir(exist_ok=True)
    command = [str(java), '-Djava.awt.headless=true', '-Djava.io.tmpdir='+str(out), '-cp', run_cp, 'dev.yg.skyboxblock.custom.'+cls]
    command += [str(scratch if cls=='HdrRegistryRegression' else scratch/'result.txt')]
    if cls=='HdrRegistryRegression' and a.hdr: command.append(str(Path(a.hdr).resolve()))
    start = time.monotonic()
    run(command, out/(cls+'.log'))
    result['tests'].append({'name':cls,'seconds':round(time.monotonic()-start,3),'result':'PASS'})
result['textures'] = []
for image in sorted((out/'HdrRegistryRegression').rglob('textures/sky/*.png')):
    result['textures'].append({'case':image.parents[7].name if len(image.parents)>7 else str(image), 'path':str(image), 'sha256':hashlib.sha256(image.read_bytes()).hexdigest()})
(root/'build/reports').mkdir(exist_ok=True)
(root/'build/reports/hdr-integration.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2))
