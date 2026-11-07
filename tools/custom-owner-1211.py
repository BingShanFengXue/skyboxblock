#!/usr/bin/env python3
"""Hidden owned custom-sky front/rear material regression, HDR versus PNG."""
from pathlib import Path
import argparse, importlib.util, os, shutil, subprocess
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
def _java_exe():
    jh = os.environ.get('JAVA_HOME')
    if jh:
        return Path(jh) / 'bin' / 'java.exe'
    base = Path(os.environ.get('LOCALAPPDATA', '')) / 'Packages' / 'Microsoft.4297127D64EC6_8wekyb3d8bbwe' / 'LocalCache' / 'Local' / 'runtime'
    for exe in sorted(base.glob('*/windows-x64/*/bin/java.exe')):
        return exe
    raise SystemExit('set JAVA_HOME (JDK 21 for 1.21.1) or install a Minecraft launcher runtime')

p=argparse.ArgumentParser();p.add_argument('--label',required=True);p.add_argument('--jar',type=Path,default=ROOT/'build/libs/skyboxblock-1.0.7-test.1-hdr.jar');p.add_argument('--profile',choices=['vanilla','iris'],default='iris');p.add_argument('--fsr',type=int,default=2);p.add_argument('--quick',action='store_true');a=p.parse_args()
s=importlib.util.spec_from_file_location('owner_r30',ROOT/'tools/run-r30-probe.py');r=importlib.util.module_from_spec(s);s.loader.exec_module(r)
r.launch.JAVA=_java_exe()
def prep(instance,config):
 studio=config/'skyboxblock/skyboxes';studio.mkdir(parents=True)
 shutil.copy2(ROOT.parent/'skyboxblock素材/04-定稿/kloppenheim_06_puresky_4k.hdr',studio/'front.hdr')
 shutil.copy2(ROOT/'build/hdr-integration/HdrRegistryRegression/real4k/config/skyboxblock/generated/assets/skyboxblock/textures/sky/hdr_test.png',studio/'front.png')
 Image.new('RGB',(1024,512),(180,180,180)).save(studio/'gray.png')
 Image.new('RGB',(1024,512),(240,40,90)).save(studio/'pink.png')
 (studio/'01_owner.conf').write_text('enable = true\n'+''.join(f'{i} = {{ id = "skyboxblock:{i}" picture = "{pic}" creative_inventory = true lang_en_us = {{ name = "{i}" }} }}\n' for i,pic in [('owner_hdr','front.hdr'),('owner_png','front.png'),('owner_gray','gray.png'),('owner_pink','pink.png')]),encoding='utf-8')
r.prepare_fixture=prep
def phases(*unused):
 phases=[];angles=[(0,0),(18,12),(-26,-12),(32,18)]
 if a.quick:angles=[(18,12)]
 for front in ['hdr','png','builtin']:
  for ai,(yaw,pitch) in enumerate(angles[:1] if front=='builtin' else angles):
   for behind,mat in [('base','minecraft:air'),('gray','skyboxblock:owner_gray'),('white','skyboxblock:white_sky_block'),('pink','skyboxblock:owner_pink')]:
    phases.append(dict(name=f'{front}-a{ai}-{behind}',material=('skyboxblock:white_sky_block' if front=='builtin' else 'skyboxblock:owner_'+front),panelWidth=48,panelHeight=40,warmupSeconds=1.5,sampleSeconds=1.5,yaw=yaw,pitch=pitch,commands=['fill -5 135 5 5 147 13 minecraft:air','fill -2 139 6 2 143 10 '+mat]))
 return phases
r.phases=phases
original=subprocess.Popen
def popen(command,*args,**kw):
 if isinstance(command,list) and 'net.fabricmc.loader.impl.launch.knot.KnotClient' in command:
  scratch=ROOT/'build/render-regression'/a.label/'tmp';scratch.mkdir(exist_ok=True)
  command[1:1]=['-Djava.io.tmpdir='+str(scratch),'-Djna.tmpdir='+str(scratch),'-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8']
 return original(command,*args,**kw)
subprocess.Popen=popen
try:
 argv=['--label',a.label,'--jar',str(a.jar.resolve()),'--probe',str(ROOT/'build/libs/skybox-validation-probe-r38.jar'),'--profile',a.profile,'--mode','smoke','--terrain','flat','--render-distance','6','--max-fps','60','--heap','3G','--warmup','1.5','--sample','1.5','--timeout','480']
 if a.profile=='iris':argv+=['--shader','iterationRP Alpha 0.8.31.zip','--fsr',str(a.fsr)]
 raise SystemExit(r.main(argv))
finally:subprocess.Popen=original
