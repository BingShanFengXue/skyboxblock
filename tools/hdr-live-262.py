#!/usr/bin/env python3
"""Real 26.2 hidden-window HDR/PNG captures and client folder command."""
import argparse, ctypes, importlib.util, json, shutil, subprocess, time, uuid
from pathlib import Path
from types import SimpleNamespace
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--name',required=True);p.add_argument('--shader',default='off');p.add_argument('--fsr',type=int,default=-1);a=p.parse_args()
s=importlib.util.spec_from_file_location('hdr_runtime262',ROOT/'mc26.2/tools/runtime-regression.py');r=importlib.util.module_from_spec(s);s.loader.exec_module(r)
case,game,control=r.paths(a.name)
r.prepare(SimpleNamespace(name=a.name,mod=str(ROOT/'mc26.2/build/libs/skyboxblock-1.0.7-test.1-hdr-port.1-mc26.2.jar'),dh=False,plain=a.shader=='off'))
options=game/'options.txt';text=options.read_text(encoding='utf-8');options.write_text(text.replace('lang:zh_cn','lang:en_us'),encoding='utf-8')
studio=game/'config/skyboxblock/skyboxes';studio.mkdir(parents=True)
shutil.copy2(ROOT.parent/'skyboxblock素材/04-定稿/kloppenheim_06_puresky_4k.hdr',studio/'真实全景 HDR.hdr')
shutil.copy2(ROOT/'build/hdr-integration/HdrRegistryRegression/real4k/config/skyboxblock/generated/assets/skyboxblock/textures/sky/hdr_test.png',studio/'reference.png')
(studio/'01_hdr.conf').write_text('enable = true\nhdr = { id = "skyboxblock:hdr_live" picture = "真实全景 HDR.hdr" creative_inventory = true lang_en_us = { name = "HDR Live" } }\npng = { id = "skyboxblock:png_live" picture = "reference.png" creative_inventory = true }\n',encoding='utf-8')
if a.shader!='off':
 (game/'config/iris.properties').write_text('enableShaders=true\nshaderPack='+a.shader+'\nallowUnknownShaders=false\n',encoding='utf-8')
 (game/'shaderpacks'/(a.shader+'.txt')).write_text('FSR2_SCALE='+str(a.fsr)+'\nMOTION_BLUR=false\n',encoding='utf-8')
scratch=case/'tmp';scratch.mkdir()
monitor_log=(case/'folder-monitor.log').open('wb')
monitor=subprocess.Popen(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'tools/hdr-folder-window-check.ps1'),'-TargetPath',str(studio),'-Evidence',str(case/'folder-open.json'),'-TimeoutSeconds','360'],stdout=monitor_log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
process=None
try:
 deadline=time.monotonic()+20
 while not (case/'folder-open.json.ready').exists():
  if monitor.poll() is not None or time.monotonic()>deadline:raise RuntimeError('folder monitor not ready')
  time.sleep(.1)
 r.launch(SimpleNamespace(name=a.name,jvm=['-Djava.io.tmpdir='+str(scratch),'-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8']))
 record=json.loads((case/'process.json').read_text());pid=record['pid']
 def call(payload,timeout=180):
  return r.call(SimpleNamespace(name=a.name,json=json.dumps(payload),file=None,timeout=timeout))
 boot=call({'id':'hdr-boot','frames':5})
 setup=['gamemode spectator @a','time set 6000','weather clear','tp @a 0.5 -56 -8.5 0 0','fill -10 -63 3 10 -45 3 skyboxblock:hdr_live']
 results=[]
 results.append(call({'id':'hdr','commands':setup,'frames':120,'capture':'hdr.png'}))
 results.append(call({'id':'png-control','commands':['fill -10 -63 3 10 -45 3 skyboxblock:png_live'],'frames':120,'capture':'png-control.png'}))
 results.append(call({'id':'hdr-occluded','commands':['fill -10 -63 3 10 -45 3 skyboxblock:hdr_live','fill -1 -58 -1 1 -52 -1 minecraft:stone'],'frames':120,'capture':'hdr-occluded.png'}))
 results.append(call({'id':'folder-open','commands':['skyboxblock','skyboxblock env list','skyboxblock env time set tomorrow'],'clientCommands':['skyboxcustom open'],'frames':4,'capture':'folder.png'}))
 messages=[message for outcome in results[-1]['commands'] for message in outcome.get('messages',[])]
 assert any('Vanilla sky environment attributes' in message for message in messages),messages
 assert any('Unknown time' in message for message in messages),messages
 for result in results:
  assert result['ok'] and not result['mouseGrabbed'] and not result['windowFocused'] and not result['windowVisible'],result
 monitor.wait(timeout=15)
 folder=json.loads((case/'folder-open.json').read_text(encoding='utf-8-sig'));assert folder['ok'],folder
 call({'id':'stop','stop':True},timeout=30)
 summary={'ok':True,'jar':str(ROOT/'mc26.2/build/libs/skyboxblock-1.0.7-test.1-hdr-port.1-mc26.2.jar'),'pid':pid,'captures':results,'folder':folder,'shader':a.shader,'fsr':a.fsr}
 (case/'hdr-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print('HDR LIVE 26.2 PASS',case)
finally:
 if (case/'process.json').exists():
  pid=json.loads((case/'process.json').read_text())['pid']
  if r.process_alive(pid):subprocess.run(['taskkill','/PID',str(pid),'/T','/F'],check=False,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 if monitor.poll() is None:monitor.terminate();monitor.wait(timeout=10)
 monitor_log.close()
