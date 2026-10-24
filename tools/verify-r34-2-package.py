"""Pin the final native-background revision; preserve artist assets and prior exposure/ray implementation."""
from pathlib import Path
import hashlib,json,zipfile
ROOT=Path(__file__).resolve().parents[1]
def main():
 jar=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.2.jar';base=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.jar';r33=ROOT/'build/libs/skyboxblock-1.0.3-test.3-r33.jar';checks=[]
 def need(n,ok):checks.append(dict(name=n,passed=bool(ok)))
 with zipfile.ZipFile(jar) as z,zipfile.ZipFile(base) as old,zipfile.ZipFile(r33) as b:
  changed=[n for n in old.namelist() if n in z.namelist() and old.read(n)!=z.read(n)]
  allowed={'META-INF/MANIFEST.MF','fabric.mod.json','dev/yg/skyboxblock/client/SkyWorldRenderer.class','dev/yg/skyboxblock/client/SkyWorldRenderer$Frame.class','dev/yg/skyboxblock/client/SkySurfaceLayer.class','assets/skyboxblock/shaders/core/sky_surface_composite.json','assets/skyboxblock/shaders/core/sky_surface_composite.fsh'}
  need('changes restricted to native repair and regression metadata',all(n in allowed or n.startswith('dev/yg/skyboxblock/client/RenderRegression') for n in changed))
  need('no added or removed members',set(z.namelist())==set(old.namelist()))
  need('manifest only version changed',z.read('META-INF/MANIFEST.MF')==old.read('META-INF/MANIFEST.MF').replace(b'1.0.3-test.4-r34',b'1.0.3-test.4-r34.2'))
  need('new version',json.loads(z.read('fabric.mod.json'))['version']=='1.0.3-test.4-r34.2')
  for n in b.namelist():
   if n.endswith('.png') or n.startswith(('assets/skyboxblock/models/','assets/skyboxblock/blockstates/','assets/skyboxblock/lang/','data/')):need('preserved '+n,z.read(n)==b.read(n))
  for name in ['SkyExposureMeter','ItrpSkyBridge','SkyTemporalEdgeMask','SkyRenderMath']:
   n='dev/yg/skyboxblock/client/'+name+'.class';need('preserved '+name,z.read(n)==b.read(n))
  for name in ['world_sky','sky_material_background','sky_meter_proxy']:
   n='assets/skyboxblock/shaders/core/'+name+'.fsh';need('preserved '+name,z.read(n)==b.read(n))
  shader=z.read('assets/skyboxblock/shaders/core/sky_surface_composite.fsh').decode('utf-8')
  need('native repair replaces with one generated-sky/backdrop composition','fragColor.rgb+(1.0-fragColor.a)*background,1.0' in shader)
  need('native clear depth keeps previous fringe','if(!(depth>0.0 && depth<1.0)) discard' in shader)
  cfg=json.loads(z.read('assets/skyboxblock/shaders/core/sky_surface_composite.json'));need('repair disabled by default',all(u['values']==[0] for u in cfg['uniforms'] if u['name'] in ['FullCoverageOnly','NativeBackgroundEnabled']))
  need('premultiplied RGB and preserved destination alpha',cfg['blend']==dict(func='add',srcrgb='one',dstrgb='1-srcalpha',srcalpha='zero',dstalpha='one'))
  need('no diagnostic probe in production',not any(n.startswith(('dev/yg/skyboxprobe/','dev/yg/skyboxfixture/')) for n in z.namelist()))
 failures=[c for c in checks if not c['passed']];report=dict(version='1.0.3-test.4-r34.2',jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),checks=len(checks),passedChecks=len(checks)-len(failures),passed=not failures,failures=failures,changedFromR34=changed,validation=checks)
 (ROOT/'build/reports/r34-2-package-audit.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps({k:v for k,v in report.items() if k!='validation'},indent=2));return bool(failures)
if __name__=='__main__':raise SystemExit(main())
