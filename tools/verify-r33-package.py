#!/usr/bin/env python3
"""Retain R32 render behavior and exact 4K resources; audit R33 meter-only implementation."""
from pathlib import Path
import hashlib,json,zipfile
ROOT=Path(__file__).resolve().parents[1]
JAR=ROOT/'build/libs/skyboxblock-1.0.3-test.3-r33.jar'
BASE=ROOT.parent/'output/skyboxblock-1.0.3-test.2-r32-4k/skyboxblock-1.0.3-test.2-r32-4k.jar'
checks=[]
def need(name,ok):checks.append(dict(name=name,passed=bool(ok)))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    with zipfile.ZipFile(JAR) as z,zipfile.ZipFile(BASE) as old:
        names=set(z.namelist());meta=json.loads(z.read('fabric.mod.json'));need('R33 version',meta['version']=='1.0.3-test.3-r33')
        lang=json.loads(z.read('assets/skyboxblock/lang/zh_cn.json'));need('Clear Sky A/B names retained',lang['block.skyboxblock.kloppenheim_sky_block']=='天空盒方块（晴空·A）' and lang['block.skyboxblock.syferfontein_sky_block']=='天空盒方块（晴空·B）')
        need('optional compute hook registered','IrisExposureComputeMixin' in json.loads(z.read('skyboxblock.mixins.json'))['client'])
        need('no probes bundled',not any(n.startswith(('dev/yg/skyboxprobe/','dev/yg/skyboxfixture/')) for n in names))
        for n in sorted(old.namelist()):
            if n.endswith('.png') or n.startswith(('assets/skyboxblock/shaders/','assets/skyboxblock/models/','assets/skyboxblock/blockstates/','data/')):
                need('preserved 4K baseline resource '+n,n in names and z.read(n)==old.read(n))
            if n.endswith('.class') and not any(s in n for s in ['client/RenderRegression','client/SkyWorldRenderer','client/SkyboxBlockClient','client/ItrpSkyBridge']):
                need('unchanged production class '+n,n in names and z.read(n)==old.read(n))
        for n in ['assets/skyboxblock/shaders/core/sky_meter_proxy.vsh','assets/skyboxblock/shaders/core/sky_meter_proxy.fsh','assets/skyboxblock/shaders/core/sky_meter_proxy.json','dev/yg/skyboxblock/client/SkyExposureMeter.class','dev/yg/skyboxblock/mixin/IrisExposureComputeMixin.class']:
            need('meter resource '+n,n in names)
        shader=z.read('assets/skyboxblock/shaders/core/sky_meter_proxy.fsh').decode('utf-8')
        need('meter guards depth provenance','texture(WorldOpaqueDepth,dq).r-own.r' in shader)
        need('explicit neutral metering floor',',.18)' in shader)
        need('reference finite half-float range','min(luminance/exposure,2048.0)' in shader)
        need('display color shaders untouched',all(z.read('assets/skyboxblock/shaders/core/'+n+'.fsh')==old.read('assets/skyboxblock/shaders/core/'+n+'.fsh') for n in ['world_sky','sky_resolve','sky_material_background']))
        # Decode headers, not images: both actual packaged sources remain 4096x2048.
        for s in ['kloppenheim','syferfontein']:
            import struct
            data=z.read('assets/skyboxblock/textures/sky/'+s+'.png');need(s+' 4K retained',struct.unpack('>II',data[16:24])==(4096,2048))
    need('math 389385 unchanged','PASS: 389385 assertions' in (ROOT/'build/reports/render-math-regression.txt').read_text(encoding='utf-8'))
    failures=[c for c in checks if not c['passed']]
    result=dict(version='1.0.3-test.3-r33',jarSha256=sha(JAR),bytes=JAR.stat().st_size,checks=len(checks),passedChecks=len(checks)-len(failures),failedChecks=len(failures),failures=failures,validation=checks)
    (ROOT/'build/reports/r33-package-audit.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='validation'},indent=2));return 0 if not failures else 1
if __name__=='__main__':raise SystemExit(main())
