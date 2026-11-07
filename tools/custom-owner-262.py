#!/usr/bin/env python3
"""26.2 hidden front/rear custom material checks using real client commands/captures."""
from pathlib import Path
from types import SimpleNamespace
import argparse,importlib.util,json,shutil,subprocess,time
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--label',required=True);p.add_argument('--jar',type=Path,default=ROOT/'mc26.2/build/libs/skyboxblock-1.0.7-test.2-owner-port.1-mc26.2.jar');p.add_argument('--profile',choices=['vanilla','iris'],default='iris');p.add_argument('--fsr',type=int,default=2);p.add_argument('--quick',action='store_true');a=p.parse_args()
s=importlib.util.spec_from_file_location('owner262',ROOT/'mc26.2/tools/runtime-regression.py');r=importlib.util.module_from_spec(s);s.loader.exec_module(r)
r.prepare(SimpleNamespace(name=a.label,mod=str(a.jar.resolve()),dh=False,plain=a.profile=='vanilla'));case,game,control=r.paths(a.label)
studio=game/'config/skyboxblock/skyboxes';studio.mkdir(parents=True)
shutil.copy2(ROOT.parent/'skyboxblock素材/04-定稿/kloppenheim_06_puresky_4k.hdr',studio/'front.hdr');shutil.copy2(ROOT/'build/hdr-integration/HdrRegistryRegression/real4k/config/skyboxblock/generated/assets/skyboxblock/textures/sky/hdr_test.png',studio/'front.png')
for name,color in [('gray',(180,180,180)),('pink',(240,40,90))]:Image.new('RGB',(1024,512),color).save(studio/(name+'.png'))
(studio/'01_owner.conf').write_text('enable = true\n'+''.join(f'{i} = {{ id = "skyboxblock:{i}" picture = "{pic}" creative_inventory = true }}\n' for i,pic in [('owner_hdr','front.hdr'),('owner_png','front.png'),('owner_gray','gray.png'),('owner_pink','pink.png')]),encoding='utf-8')
if a.profile=='iris':
 (game/'config/iris.properties').write_text('enableShaders=true\nshaderPack=iterationRP Alpha 0.8.31.zip\nallowUnknownShaders=false\n',encoding='utf-8');(game/'shaderpacks/iterationRP Alpha 0.8.31.zip.txt').write_text(f'FSR2_SCALE={a.fsr}\nMOTION_BLUR=false\n',encoding='utf-8')
scratch=case/'tmp';scratch.mkdir();out=case/'owner-results';out.mkdir();results=[]
def call(payload,timeout=180):return r.call(SimpleNamespace(name=a.label,json=json.dumps(payload),file=None,timeout=timeout))
try:
 r.launch(SimpleNamespace(name=a.label,jvm=['-Djava.io.tmpdir='+str(scratch),'-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8']));call({'id':'boot','frames':120})
 angles=[(18,12)] if a.quick else [(0,0),(18,12),(-26,-12),(32,18)]
 for front in ['hdr','png','builtin']:
  for ai,(yaw,pitch) in enumerate(angles[:1] if front=='builtin' else angles):
   for behind,mat in [('base','minecraft:air'),('gray','skyboxblock:owner_gray'),('white','skyboxblock:white_sky_block'),('pink','skyboxblock:owner_pink')]:
    name=f'{front}-a{ai}-{behind}';index=len(results);capture=f'{index:02d}-{name}.png';commands=['gamemode spectator @a',f'tp @a 0.5 4 -8.5 {yaw} {pitch}','fill -24 -15 3 23 24 3 '+('skyboxblock:white_sky_block' if front=='builtin' else 'skyboxblock:owner_'+front),'fill -5 -1 5 5 11 13 minecraft:air','fill -2 3 6 2 7 10 '+mat]
    result=call({'id':name,'commands':commands,'frames':90,'capture':capture});result['name']=name;result['index']=index;result['camera']=[result['cameraX'],result['cameraY'],result['cameraZ']];result['serverGeometry']={'originX':-24,'originY':-15,'originZ':3,'requestedPanelBlocks':1920};results.append(result);shutil.copy2(control/capture,out/capture)
    (out/(capture[:-4]+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 call({'id':'stop','stop':True},30)
 (out/'run-summary.json').write_text(json.dumps({'complete':True,'phases':results,'actualGameDir':str(game),'targetVersion':'26.2','cameraData':'actual captured client VP and camera; serverGeometry is the explicit fill command rectangle'},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 print('OWNER 26.2 EXECUTION COMPLETE',case,flush=True)
finally:
 if (case/'process.json').exists():
  pid=json.loads((case/'process.json').read_text())['pid'];deadline=time.monotonic()+10
  while r.process_alive(pid) and time.monotonic()<deadline:time.sleep(.1)
  if r.process_alive(pid):subprocess.run(['taskkill','/PID',str(pid),'/T','/F'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
