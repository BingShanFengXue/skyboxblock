#!/usr/bin/env python3
"""Audit the actual R35 remapped jar and protected R34.2 assets/classes, never replace frozen artifacts."""
import argparse,hashlib,json,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar',type=Path,default=ROOT/'build/libs/skyboxblock-1.0.4-test.1-r35.jar')
    p.add_argument('--baseline',type=Path,default=ROOT.parent/'output/skyboxblock-1.0.3-test.4-r34.2/skyboxblock-1.0.3-test.4-r34.2.jar')
    p.add_argument('--output',type=Path,default=ROOT/'build/reports/r35-package-audit.json');a=p.parse_args();checks=[]
    def check(name,passed,**extra):checks.append(dict(name=name,passed=bool(passed),**extra))
    with zipfile.ZipFile(a.jar) as z,zipfile.ZipFile(a.baseline) as base:
        names=set(z.namelist());old=set(base.namelist());meta=json.loads(z.read('fabric.mod.json'))
        check('id_version',meta['id']=='skyboxblock' and meta['version']=='1.0.4-test.1-r35',actual=meta)
        for name in ['dev/yg/skyboxblock/SkyEnvironment.class','dev/yg/skyboxblock/client/SkyEnvironmentMath.class',
                'dev/yg/skyboxblock/client/SkyboxCommands$EnvironmentValueArgumentType.class']:
            check('new_class:'+name,name in names)
        check('test_probe_not_in_production',not any(n.startswith('dev/yg/skyboxprobe/') for n in names))
        for name in ['SkyEnvironmentRegression','SkyCommandRegression','SkyEnvironmentRenderRegression','SkyEnvironmentBoundaryRegression']:
            check('no_test_class:'+name,not any(n.endswith('/'+name+'.class') for n in names))
        check('license',any(n.startswith('LICENSE') for n in names))
        check('credits',b'R35 environment attributes' in z.read('AUTHORS.md'))
        check('third_party_notices_match_current_source',z.read('THIRD_PARTY_NOTICES.md')==(ROOT/'THIRD_PARTY_NOTICES.md').read_bytes(),
            equalsFrozenBaseline=z.read('THIRD_PARTY_NOTICES.md')==base.read('THIRD_PARTY_NOTICES.md'),
            explanation='Current project notices include the pre-existing 2026-10-24 asset attribution supplement; frozen jars remain unchanged.')
        for name in sorted(n for n in old if n.endswith('.png')):
            check('immutable_texture:'+name,name in names and z.read(name)==base.read(name))
        for name in ['SkyWorldRenderer','SkySurfaceLayer','SkyTemporalEdgeMask','SkyExposureMeter','ItrpSkyBridge',
                'DistantSkyDepth','ModCompat','SkyGlState','SkyCoverage','SkyRenderMath']:
            entry='dev/yg/skyboxblock/client/'+name+'.class'
            check('protected_pipeline:'+name,entry in names and z.read(entry)==base.read(entry))
        for stem in ['vanilla_celestial','vanilla_stars','world_sky','sky_resolve','sky_surface_composite','sky_meter_proxy']:
            for suffix in ['json','fsh','vsh']:
                entry='assets/skyboxblock/shaders/core/'+stem+'.'+suffix
                if entry in old:check('protected_shader:'+stem+'.'+suffix,z.read(entry)==base.read(entry))
        shader=json.loads(z.read('assets/skyboxblock/shaders/core/vanilla_cloud.json'))
        uniforms={u['name']:u for u in shader['uniforms']}
        check('cloud_opacity_default_one',uniforms['CloudOpacity']['values']==[1])
        check('cloud_opacity_used',b'CloudOpacity *' in z.read('assets/skyboxblock/shaders/core/vanilla_cloud.fsh'))
        check('safe_fog_edges',b'FogEnd <= FogStart' in z.read('assets/skyboxblock/shaders/core/vanilla_background.fsh'))
    result=dict(passed=sum(c['passed'] for c in checks),total=len(checks),checks=checks,
        failed=[c for c in checks if not c['passed']],jar=str(a.jar),sha256=hashlib.sha256(a.jar.read_bytes()).hexdigest(),
        baseline=str(a.baseline),baselineSha256=hashlib.sha256(a.baseline.read_bytes()).hexdigest())
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(json.dumps({k:result[k] for k in ['passed','total','failed','sha256']},indent=2,ensure_ascii=False))
    return 0 if result['passed']==result['total'] else 1
if __name__=='__main__':raise SystemExit(main())