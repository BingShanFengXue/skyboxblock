"""Audit the post-review candidate and restrict the delta from the original R34."""
from pathlib import Path
import hashlib,json,zipfile
ROOT=Path(__file__).resolve().parents[1]
def main():
    jar=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.1.jar';base=ROOT/'build/libs/skyboxblock-1.0.3-test.4-r34.jar';r33=ROOT/'build/libs/skyboxblock-1.0.3-test.3-r33.jar';checks=[]
    def need(name,ok):checks.append(dict(name=name,passed=bool(ok)))
    with zipfile.ZipFile(jar) as z,zipfile.ZipFile(base) as old,zipfile.ZipFile(r33) as b:
        changed=[n for n in old.namelist() if n in z.namelist() and old.read(n)!=z.read(n)]
        allowed={'META-INF/MANIFEST.MF','fabric.mod.json','dev/yg/skyboxblock/client/SkyWorldRenderer.class','dev/yg/skyboxblock/client/SkySurfaceLayer.class','assets/skyboxblock/shaders/core/sky_surface_composite.json','assets/skyboxblock/shaders/core/sky_surface_composite.fsh'}
        need('post-review delta limited to native late coverage',set(changed)==allowed)
        need('no added or removed archive members',set(z.namelist())==set(old.namelist()))
        need('manifest differs only in implementation version',z.read('META-INF/MANIFEST.MF')==old.read('META-INF/MANIFEST.MF').replace(b'1.0.3-test.4-r34',b'1.0.3-test.4-r34.1'))
        need('new version',json.loads(z.read('fabric.mod.json'))['version']=='1.0.3-test.4-r34.1')
        for n in b.namelist():
            if n.endswith('.png') or n.startswith(('assets/skyboxblock/models/','assets/skyboxblock/blockstates/','assets/skyboxblock/lang/','data/')):
                need('unchanged R33 resource '+n,z.read(n)==b.read(n))
        for name in ['SkyExposureMeter','ItrpSkyBridge','SkyTemporalEdgeMask','SkyRenderMath']:
            n='dev/yg/skyboxblock/client/'+name+'.class';need('preserved '+name,z.read(n)==b.read(n))
        for name in ['world_sky','sky_material_background','sky_meter_proxy']:
            n='assets/skyboxblock/shaders/core/'+name+'.fsh';need('preserved '+name,z.read(n)==b.read(n))
        need('partial coverage excluded only in explicit repair',b'FullCoverageOnly != 0 && fragColor.a<0.99999' in z.read('assets/skyboxblock/shaders/core/sky_surface_composite.fsh'))
        need('default full-coverage flag is zero',next(u for u in json.loads(z.read('assets/skyboxblock/shaders/core/sky_surface_composite.json'))['uniforms'] if u['name']=='FullCoverageOnly')['values']==[0])
        need('no validation probe bundled',not any(n.startswith(('dev/yg/skyboxprobe/','dev/yg/skyboxfixture/')) for n in z.namelist()))
    failures=[c for c in checks if not c['passed']]
    result=dict(jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),bytes=jar.stat().st_size,checks=len(checks),passedChecks=len(checks)-len(failures),failures=failures,passed=not failures,changedFromR34=changed,validation=checks)
    (ROOT/'build/reports/r34-1-package-audit.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps({k:v for k,v in result.items() if k!='validation'},indent=2));return bool(failures)
if __name__=='__main__':raise SystemExit(main())
