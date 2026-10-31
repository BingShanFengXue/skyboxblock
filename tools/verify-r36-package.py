#!/usr/bin/env python3
"""Independent R36 package audit: server-authority classes exist, R35 pipeline stays byte-identical."""
import argparse,hashlib,json,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar',type=Path,default=ROOT/'build/libs/skyboxblock-1.0.4-test.2-r36.jar')
    p.add_argument('--r35',type=Path,default=ROOT.parent/'output/skyboxblock-1.0.4-test.1-r35/skyboxblock-1.0.4-test.1-r35.jar')
    p.add_argument('--r34',type=Path,default=ROOT.parent/'output/skyboxblock-1.0.3-test.4-r34.2/skyboxblock-1.0.3-test.4-r34.2.jar')
    p.add_argument('--output',type=Path,default=ROOT/'build/reports/r36-package-audit.json');a=p.parse_args()
    checks=[]
    def check(name,passed,**extra):checks.append(dict(name=name,passed=bool(passed),**extra))
    with zipfile.ZipFile(a.jar) as z,zipfile.ZipFile(a.r35) as r35,zipfile.ZipFile(a.r34) as r34:
        names=set(z.namelist());meta=json.loads(z.read('fabric.mod.json'))
        check('id_version',meta['id']=='skyboxblock' and meta['version']=='1.0.4-test.2-r36',actual=meta.get('version'))
        for entry in ['dev/yg/skyboxblock/SkyboxSettings.class','dev/yg/skyboxblock/SkyTimeClock.class',
                'dev/yg/skyboxblock/server/SkyboxServerConfig.class','dev/yg/skyboxblock/server/SkyboxServerCommands.class',
                'dev/yg/skyboxblock/server/SkyboxServerConfig$Store.class',
                'dev/yg/skyboxblock/network/SkyboxConfigPayload.class','dev/yg/skyboxblock/network/SkyboxNetworking.class',
                'dev/yg/skyboxblock/client/SkyboxServerState.class','dev/yg/skyboxblock/client/SkyCloudStencil.class',
                'dev/yg/skyboxblock/client/SkyCloudStencil$Scope.class']:
            check('new_class:'+entry,entry in names)
        # The old client-only command tree must not ship twice.
        check('no_client_command_class','dev/yg/skyboxblock/client/SkyboxCommands.class' not in names)
        check('probe_not_in_production',not any(n.startswith('dev/yg/skyboxprobe/') for n in names))
        for test in ['SkyEnvironmentRegression','SkyTimeRegression','SkyEnvironmentRenderRegression','SkyCommandRegression','SkyEnvironmentBoundaryRegression','SkyboxServerBackendRegression','ServerCommandRegression']:
            check('no_test_class:'+test,not any(n.endswith('/'+test+'.class') for n in names))
        check('license_present',any(n.startswith('LICENSE') for n in names))
        check('credits_embedded',b'Bing6512' in z.read('AUTHORS.md') and b'AI-assisted engineering credits' in z.read('AUTHORS.md'),
              note='R36 contributor credit is recorded in docs/R36-DELIVERY-2026-10-31.md; the jar keeps the build-time credits bytes unchanged.')
        # Preserved assets and protected rendering pipeline from R34.2 baseline.
        for name in sorted(n for n in r34.namelist() if n.endswith('.png')):
            check('immutable_texture:'+name,name in names and z.read(name)==r34.read(name))
        for stem in ['SkyWorldRenderer','SkySurfaceLayer','SkyTemporalEdgeMask','SkyExposureMeter','ItrpSkyBridge',
                'DistantSkyDepth','ModCompat','SkyGlState','SkyCoverage','SkyRenderMath']:
            entry='dev/yg/skyboxblock/client/'+stem+'.class'
            check('protected_pipeline:'+stem,entry in names and z.read(entry)==r34.read(entry))
        for stem in ['vanilla_celestial','vanilla_stars','world_sky','sky_resolve','sky_surface_composite',
                'sky_meter_proxy','vanilla_background','vanilla_cloud','vanilla_dawn']:
            for suffix in ['json','fsh','vsh']:
                entry='assets/skyboxblock/shaders/core/'+stem+'.'+suffix
                if entry in r35.namelist():
                    check('r35_shader_same:'+stem+'.'+suffix,z.read(entry)==r35.read(entry))
        # R36 cloud stencil contract.
        check('cloud_opacity_uniform',b'CloudOpacity' in z.read('assets/skyboxblock/shaders/core/vanilla_cloud.json'))
        check('safe_fog_edges',b'FogEnd <= FogStart' in z.read('assets/skyboxblock/shaders/core/vanilla_background.fsh'))
    result=dict(passed=sum(c['passed'] for c in checks),total=len(checks),
        failed=[c for c in checks if not c['passed']],checks=checks,
        jar=str(a.jar),sha256=hashlib.sha256(a.jar.read_bytes()).hexdigest(),
        r35=hashlib.sha256(a.r35.read_bytes()).hexdigest(),r34=hashlib.sha256(a.r34.read_bytes()).hexdigest())
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(json.dumps({k:result[k] for k in ['passed','total','failed','sha256']},indent=2,ensure_ascii=False))
    return 0 if result['passed']==result['total'] else 1
if __name__=='__main__':raise SystemExit(main())