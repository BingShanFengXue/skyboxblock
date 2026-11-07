#!/usr/bin/env python3
"""Audit actual final JARs and preserve compact HDR delivery evidence."""
from pathlib import Path
import hashlib, json, re, shutil, zipfile
from PIL import Image, ImageDraw
ROOT=Path(__file__).resolve().parents[1]
EVIDENCE=ROOT/'docs/evidence/hdr-import-20261108';EVIDENCE.mkdir(parents=True,exist_ok=True)
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
checks={}
for rel in ['src/main/java/dev/yg/skyboxblock/custom/RadianceHdr.java','src/main/java/dev/yg/skyboxblock/custom/SkyConfigParser.java','src/test/java/dev/yg/skyboxblock/custom/RadianceHdrRegression.java','src/test/java/dev/yg/skyboxblock/custom/HdrRegistryRegression.java','src/test/java/dev/yg/skyboxblock/custom/SkyConfigRegression.java']:
 checks['sourceParity:'+rel]=(ROOT/rel).read_bytes()==(ROOT/'mc26.2'/rel).read_bytes()
for lang in ['en_us','zh_cn']:
 rel='src/main/resources/assets/skyboxblock/lang/'+lang+'.json'
 checks['localeParity:'+lang]=read(ROOT/rel)==read(ROOT/'mc26.2'/rel)
checks['localeKeyParity']=read(ROOT/'src/main/resources/assets/skyboxblock/lang/en_us.json').keys()==read(ROOT/'src/main/resources/assets/skyboxblock/lang/zh_cn.json').keys()
variants=[('1211',ROOT/'build/libs/skyboxblock-1.0.7-test.1-hdr.jar',ROOT/'build/libs/skyboxblock-1.0.5-test.1-r38.jar'),('262',ROOT/'mc26.2/build/libs/skyboxblock-1.0.7-test.1-hdr-port.1-mc26.2.jar',ROOT.parent/'skybox-mod-26.2/output/skyboxblock-1.0.6-test.1-port.2-mc26.2.jar')]
packages=[]
for version,jar,old in variants:
 with zipfile.ZipFile(jar) as z,zipfile.ZipFile(old) as baseline:
  meta=json.loads(z.read('fabric.mod.json'));unchanged=[];changed=[]
  for name in baseline.namelist():
   renderer=name.startswith('dev/yg/skyboxblock/client/') and name.endswith('.class') and '/CustomSkyCommand' not in name and '/Fixture262Client' not in name
   resource=name.startswith('assets/skyboxblock/') and '/lang/' not in name
   if renderer or resource:
    if name in z.namelist() and z.read(name)==baseline.read(name):unchanged.append(name)
    else:changed.append(name)
  checks['rendererAndAssetsUnchanged:'+version]=not changed
  checks['decoderPackaged:'+version]='dev/yg/skyboxblock/custom/RadianceHdr.class' in z.namelist()
  checks['productionNoR30Probe:'+version]=not any(n.startswith('dev/yg/skyboxprobe/') for n in z.namelist())
  packages.append({'version':version,'jar':str(jar.relative_to(ROOT)),'sha256':sha(jar),'metadataVersion':meta['version'],'unchangedRendererAndAssetCount':len(unchanged),'unexpectedChangedRenderingEntries':changed})
case_specs=[('1211','hdr-final-1211-r2'),('262','hdr-final-262-r2'),('1211','hdr-final-1211-itrp-r2'),('262','hdr-final-262-itrp-r2')]
cases=[];tiles=[]
for version,label in case_specs:
 case=ROOT/('build' if version=='1211' else 'mc26.2/build')/'render-regression'/label
 report=read(case/'hdr-pixel-validation.json');folder=read(case/'folder-open.json');checks['live:'+label]=report['ok']
 game=case if version=='1211' else case/'game';jar=next((game/'mods').glob('skyboxblock-*.jar'))
 expected=next(p['sha256'] for p in packages if p['version']==version)
 checks['finalJarIdentity:'+label]=sha(jar)==expected
 logs=(case/'client.log').read_text(encoding='utf-8',errors='replace')
 errors=[s for s in logs.splitlines() if '/ERROR]' in s]
 offline=[s for s in errors if any(v in s for v in ['Failed to fetch user properties','Failed to fetch Realms feature flags','Failed to retrieve profile key pair'])]
 other=[s for s in errors if s not in offline]
 checks['noRenderErrors:'+label]=not other
 item={'label':label,'version':version,'jarSha256':sha(jar),'validation':report,'folder':folder,'offlineAuthenticationErrorLines':offline,'otherErrorLines':other}
 cases.append(item);(EVIDENCE/(label+'.json')).write_text(json.dumps(item,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 source=case/('r30-results/00-hdr.png' if version=='1211' else 'control/hdr.png')
 source_occ=case/('r30-results/02-hdr-occluded.png' if version=='1211' else 'control/hdr-occluded.png')
 for suffix,path in [('hdr',source),('occluded',source_occ)]:
  im=Image.open(path).convert('RGB');im.thumbnail((480,300));tile=Image.new('RGB',(480,328),'#202630');tile.paste(im,((480-im.width)//2,24));ImageDraw.Draw(tile).text((8,6),label+' / '+suffix,fill='white');tiles.append(tile)
checks['allFourCases']=len(cases)==4
report={'packages':packages,'checks':checks,'ok':all(checks.values()),'cases':cases,'scope':'Radiance .hdr startup import to 8-bit display sRGB; exact runtime render path unchanged; native + pinned Iris/ITRP/FSR2, Windows OpenGL; not native floating-point HDR output, EXR, or general uncapped performance certification'}
(EVIDENCE/'acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
canvas=Image.new('RGB',(960,328*4),'#202630')
for i,tile in enumerate(tiles):canvas.paste(tile,((i%2)*480,(i//2)*328))
canvas.save(EVIDENCE/'overview.png')
for version in ['1211','262']:
 project=ROOT if version=='1211' else ROOT/'mc26.2'
 shutil.copy2(project/'build/hdr-final-build.log',EVIDENCE/(version+'-build.log'))
 for name in ['hdrDecoderTest.txt','customSkyConfigTest.txt']:
  shutil.copy2(project/'build/reports'/name,EVIDENCE/(version+'-'+name))
 shutil.copy2(project/'build/reports/hdr-integration.json',EVIDENCE/(version+'-integration.json'))
print(json.dumps({'packages':packages,'checks':checks,'ok':report['ok']},indent=2))
raise SystemExit(0 if report['ok'] else 1)
