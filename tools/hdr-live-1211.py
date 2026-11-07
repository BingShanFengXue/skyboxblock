#!/usr/bin/env python3
"""Owned, hidden-window 1.21.1 HDR/PNG A-B and real folder-command test."""
import argparse, importlib.util, json, os, shutil, subprocess, time
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def module(name,path):
 s=importlib.util.spec_from_file_location(name,path); m=importlib.util.module_from_spec(s); s.loader.exec_module(m); return m
def _java_exe():
    jh = os.environ.get('JAVA_HOME')
    if jh:
        return Path(jh) / 'bin' / 'java.exe'
    base = Path(os.environ.get('LOCALAPPDATA', '')) / 'Packages' / 'Microsoft.4297127D64EC6_8wekyb3d8bbwe' / 'LocalCache' / 'Local' / 'runtime'
    for exe in sorted(base.glob('*/windows-x64/*/bin/java.exe')):
        return exe
    raise SystemExit('set JAVA_HOME (JDK 21 for 1.21.1) or install a Minecraft launcher runtime')

p=argparse.ArgumentParser();p.add_argument('--label',required=True);p.add_argument('--profile',choices=['vanilla','iris'],default='vanilla');p.add_argument('--shader');p.add_argument('--fsr',type=int,default=-1);a=p.parse_args()
r=module('hdr_r30',ROOT/'tools/run-r30-probe.py')
r.launch.JAVA=_java_exe()
case=ROOT/'build/render-regression'/a.label
monitor=None
def prep(instance,config):
 studio=config/'skyboxblock/skyboxes';studio.mkdir(parents=True)
 shutil.copy2(ROOT.parent/'skyboxblock素材/04-定稿/kloppenheim_06_puresky_4k.hdr',studio/'真实全景 HDR.hdr')
 shutil.copy2(ROOT/'build/hdr-integration/HdrRegistryRegression/real4k/config/skyboxblock/generated/assets/skyboxblock/textures/sky/hdr_test.png',studio/'reference.png')
 (studio/'01_hdr.conf').write_text('enable = true\nhdr = { id = "skyboxblock:hdr_live" picture = "真实全景 HDR.hdr" creative_inventory = true lang_en_us = { name = "HDR Live" } }\npng = { id = "skyboxblock:png_live" picture = "reference.png" creative_inventory = true }\n',encoding='utf-8')
r.prepare_fixture=prep
def phases(*unused):
 def phase(name,material='air',**kw):return dict(name=name,material=material,panelWidth=20,panelHeight=20,warmupSeconds=4,sampleSeconds=5,**kw)
 return [phase('hdr','skyboxblock:hdr_live'),phase('png-control','skyboxblock:png_live'),phase('hdr-occluded','skyboxblock:hdr_live',commands=['fill -1 139 -1 1 144 -1 minecraft:stone']),phase('folder-open','skyboxblock:hdr_live',commands=['fill -1 139 -1 1 144 -1 minecraft:air'],clientCommands=['skyboxcustom'])]
r.phases=phases
original=subprocess.Popen
def popen(command,*args,**kw):
 global monitor
 if isinstance(command,list) and 'net.fabricmc.loader.impl.launch.knot.KnotClient' in command:
  scratch=case/'tmp';scratch.mkdir(exist_ok=True)
  command[1:1]=['-Djava.io.tmpdir='+str(scratch),'-Djna.tmpdir='+str(scratch),'-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8']
  evidence=case/'folder-open.json'
  monitor=original(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'tools/hdr-folder-window-check.ps1'),'-TargetPath',str(case/'config/skyboxblock/skyboxes'),'-Evidence',str(evidence),'-TimeoutSeconds','360'],stdout=open(case/'folder-monitor.log','wb'),stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
  deadline=time.monotonic()+20
  while not Path(str(evidence)+'.ready').exists():
   if monitor.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Folder monitor not ready')
   time.sleep(.1)
 return original(command,*args,**kw)
subprocess.Popen=popen
try:
 argv=['--label',a.label,'--jar',str(ROOT/'build/libs/skyboxblock-1.0.7-test.1-hdr.jar'),'--probe',str(ROOT/'build/libs/skybox-validation-probe-r38.jar'),'--profile',a.profile,'--mode','smoke','--terrain','flat','--render-distance','6','--max-fps','60','--heap','3G','--warmup','4','--sample','5','--timeout','360']
 if a.shader:argv+=['--shader',a.shader,'--fsr',str(a.fsr)]
 code=r.main(argv)
 if monitor:
  monitor.wait(timeout=20)
  evidence=json.loads((case/'folder-open.json').read_text(encoding='utf-8-sig'));print(json.dumps(evidence,ensure_ascii=False,indent=2));assert evidence['ok']
 print('runnerExit=',code,'(inspect runtime report; offline authentication errors are listed separately)')
finally:
 subprocess.Popen=original
 if monitor and monitor.poll() is None:monitor.terminate();monitor.wait(timeout=10)
