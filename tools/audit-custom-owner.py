#!/usr/bin/env python3
from pathlib import Path
import hashlib,json,os,shutil,zipfile
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'docs/evidence/custom-owner-20261108';OUT.mkdir(parents=True,exist_ok=True)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_text(encoding='utf-8'))
checks={};packages=[]
for v,project,old,new in [('1211',ROOT,'1.0.7-test.1-hdr','1.0.7-test.2-owner'),('262',ROOT/'mc26.2','1.0.7-test.1-hdr-port.1-mc26.2','1.0.7-test.2-owner-port.1-mc26.2')]:
 jar=project/'build/libs'/('skyboxblock-'+new+'.jar');baseline=project/'build/libs'/('skyboxblock-'+old+'.jar')
 with zipfile.ZipFile(jar) as a,zipfile.ZipFile(baseline) as b:
  changed=[n for n in sorted(set(a.namelist())|set(b.namelist())) if n not in a.namelist() or n not in b.namelist() or a.read(n)!=b.read(n)]
  unexpected=[n for n in changed if n not in ['dev/yg/skyboxblock/SkySkin.class','fabric.mod.json','META-INF/MANIFEST.MF']]
  checks['onlyIdentityChanged:'+v]=not unexpected
  packages.append({'version':v,'jar':str(jar.relative_to(ROOT)),'sha256':sha(jar),'baselineSha256':sha(baseline),'changedJarEntries':changed,'unexpectedChanges':unexpected})
 units=(project/'build/reports/material-identity.txt').read_text(encoding='utf-8');checks['identityTests:'+v]='PASS: 545' in units
 shutil.copy2(project/'build/reports/material-identity.txt',OUT/(v+'-material-identity.txt'));shutil.copy2(project/'build/custom-owner-build.log',OUT/(v+'-build.log'))
old_cases=[('1211',ROOT/'build/render-regression/custom-owner-before-quick'),('262',ROOT/'mc26.2/build/render-regression/owner262-before-quick')]
negative=[]
for v,case in old_cases:
 report=read(case/'custom-owner-check.json');checks['reproducedHDRandPNG:'+v]=report['negativeControlDetected'];negative.append(report);shutil.copy2(case/'custom-owner-check.json',OUT/(v+'-before.json'))
rows=[];phases=0;comparisons=0
for v in ['1211','262']:
 for profile in ['native','itrp-native','itrp-fsr']:
  label='owner'+v+'-fixed-'+profile;case=ROOT/('build' if v=='1211' else 'mc26.2/build')/'render-regression'/label;report=read(case/'custom-owner-check.json');checks['matrix:'+label]=report['ok'];phases+=report['phases'];comparisons+=len(report['comparisons']);rows.append({'label':label,'version':v,'report':report});shutil.copy2(case/'custom-owner-check.json',OUT/(label+'.json'))
  game=case if v=='1211' else case/'game';actual=next((game/'mods').glob('skyboxblock-*.jar'));expected=next(p['sha256'] for p in packages if p['version']==v);checks['packageIdentity:'+label]=sha(actual)==expected
  text=(case/'client.log').read_text(encoding='utf-8',errors='replace');errors=[s for s in text.splitlines() if '/ERROR]' in s and not any(t in s for t in ['Failed to fetch user properties','Failed to fetch Realms feature flags','Failed to retrieve profile key pair'])];checks['noRenderErrors:'+label]=not errors
  active='Using shaderpack: iterationRP Alpha 0.8.31.zip' in text
  checks['actualShaderMode:'+label]=(active if profile.startswith('itrp') else not active)
  state=read(case/('r30-results/run-summary.json' if v=='1211' else 'owner-results/run-summary.json'))['phases'][0]
  if v=='1211':
   checks['actualIrisHook:'+label]=bool(state['irisFinalHook'])==profile.startswith('itrp')
   checks['actualFsrMode:'+label]=(state['rasterScaleX']<.99 if profile=='itrp-fsr' else abs(state['rasterScaleX']-1)<1e-5)
  else:
   checks['actualPackActive:'+label]=bool(state['shadersActive'])==profile.startswith('itrp')
   if profile.startswith('itrp'):
    import re
    scales=[float(value) for value in re.findall(r'scale=\(\s*([-+0-9.Ee]+)',text)]
    checks['actualFsrMode:'+label]=bool(scales) and (min(scales)<.99 if profile=='itrp-fsr' else all(abs(value-1)<1e-5 for value in scales))
checks['hdrDecoderUntouched']=(ROOT/'src/main/java/dev/yg/skyboxblock/custom/RadianceHdr.java').read_bytes()==(ROOT/'mc26.2/src/main/java/dev/yg/skyboxblock/custom/RadianceHdr.java').read_bytes()
result={'ok':all(checks.values()),'checks':checks,'packages':packages,'phases':phases,'comparisons':comparisons,'negativeControls':negative,'matrix':rows,'scope':'Four angles of HDR/PNG front panels; custom gray/pink and built-in white rear cube; reversed built-in white front; native/Iris-ITRP no FSR/Iris-ITRP FSR2 for MC1.21.1 and26.2. Hidden windows, actual screenshots. No claim of all shader packs, DH or portals.'}
(OUT/'acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
canvas=Image.new('RGB',(960,328*4),'#202630');row=0
for v,oldcase in old_cases:
 newcase=ROOT/('build' if v=='1211' else 'mc26.2/build')/'render-regression'/('owner'+v+'-fixed-itrp-fsr');oldd=oldcase/('r30-results' if v=='1211' else 'owner-results');newd=newcase/('r30-results' if v=='1211' else 'owner-results')
 for front,beforeidx,afteridx in [('hdr',1,5),('png',5,21)]:
  for col,path,label in [(0,oldd/f'{beforeidx:02d}-{front}-a0-gray.png','before'),(1,newd/f'{afteridx:02d}-{front}-a1-gray.png','fixed')]:
   im=Image.open(path).convert('RGB');im.thumbnail((480,300));canvas.paste(im,((col*480)+(480-im.width)//2,row*328+25));ImageDraw.Draw(canvas).text((col*480+8,row*328+6),v+' '+front.upper()+' / '+label,fill='white')
  row+=1
canvas.save(OUT/'before-after.png')
source=Path(os.environ.get('SKYBOX_USER_REPORT',''))
if source.is_file(): shutil.copy2(source,OUT/'user-report.png')

print(json.dumps({'ok':result['ok'],'checks':checks,'packages':packages,'phases':phases,'comparisons':comparisons},indent=2));raise SystemExit(0 if result['ok'] else 1)
