"""Audit scoped R34 production changes against frozen R33, including actual PNG and meter bytecode."""
from pathlib import Path
import zipfile,json,hashlib,struct
ROOT=Path(__file__).resolve().parents[1]
def main():
 jar=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.jar';old=ROOT/'build/libs/skyboxblock-1.0.3-test.3-r33.jar';checks=[]
 def need(name,ok):checks.append(dict(name=name,passed=bool(ok)))
 with zipfile.ZipFile(jar) as z,zipfile.ZipFile(old) as b:
  need('correct R34 version',json.loads(z.read('fabric.mod.json'))['version']=='1.0.3-test.4-r34')
  allowed=['client/RenderRegression','client/SkyWorldRenderer','client/SkyboxBlockClient','assets/skyboxblock/shaders/core/sky_resolve.']
  for n in b.namelist():
   if n.endswith(('.class','.png','.fsh','.vsh','.json')) and n!='fabric.mod.json' and not any(s in n for s in allowed):
    need('unchanged R33 '+n,n in z.namelist() and z.read(n)==b.read(n))
  for style in ['kloppenheim','syferfontein']:
   data=z.read('assets/skyboxblock/textures/sky/'+style+'.png');need(style+' exact 4K',struct.unpack('>II',data[16:24])==(4096,2048) and data==b.read('assets/skyboxblock/textures/sky/'+style+'.png'))
  for n in ['SkyExposureMeter','ItrpSkyBridge','SkyTemporalEdgeMask','SkyRenderMath']:
   path='dev/yg/skyboxblock/client/'+n+'.class';need('preserved '+n,z.read(path)==b.read(path))
  blend=json.loads(z.read('assets/skyboxblock/shaders/core/sky_surface_composite.json'))['blend'];need('premultiplied compositing',blend['srcrgb']=='one' and blend['dstrgb']=='1-srcalpha' and blend['srcalpha']=='zero' and blend['dstalpha']=='one')
  need('no validation probe bundled',not any(n.startswith(('dev/yg/skyboxprobe/','dev/yg/skyboxfixture/')) for n in z.namelist()))
 failures=[c for c in checks if not c['passed']]
 report=dict(jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),bytes=jar.stat().st_size,checks=len(checks),passedChecks=len(checks)-len(failures),failedChecks=len(failures),passed=not failures,failures=failures,validation=checks)
 out=ROOT/'build/reports/r34-package-audit.json';out.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps({k:v for k,v in report.items() if k!='validation'},indent=2));return bool(failures)
if __name__=='__main__':raise SystemExit(main())
