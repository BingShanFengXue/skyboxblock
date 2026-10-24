#!/usr/bin/env python3
"""Actual R32 binary audit with byte-preservation checks for the vanilla sky and all image assets."""
from pathlib import Path
import hashlib
import json
import re
import zipfile
ROOT=Path(__file__).resolve().parents[1]
VERSION='1.0.3-test.2-r32'
JAR=ROOT/f'build/libs/skyboxblock-{VERSION}.jar'
BASE=ROOT/'build/libs/skyboxblock-1.0.3-test.1-r31.jar'
checks=[]
def need(name,condition,**info):checks.append(dict(name=name,passed=bool(condition),**info))
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def block(text,start):
    begin=text.index('{',start);depth=1;end=begin+1
    while depth:
        if text[end]=='{':depth+=1
        elif text[end]=='}':depth-=1
        end+=1
    return text[start:end],end

def main():
    need('frozen R31 baseline identity',sha(BASE)=='c8941ec8867a191478196955404d6f0a3ad889c5e76bb46e2c3b6a33dffae287')
    with zipfile.ZipFile(JAR) as jar,zipfile.ZipFile(BASE) as old:
        names=set(jar.namelist());metadata=json.loads(jar.read('fabric.mod.json'));zh=json.loads(jar.read('assets/skyboxblock/lang/zh_cn.json'));en=json.loads(jar.read('assets/skyboxblock/lang/en_us.json'))
        need('version exact',metadata['version']==VERSION)
        need('author retained',metadata['authors']==['Bing6512'])
        need('Clear Sky A exact',zh['block.skyboxblock.kloppenheim_sky_block']=='天空盒方块（晴空·A）' and en['block.skyboxblock.kloppenheim_sky_block']=='Sky Box Block (Clear Sky A)')
        need('Clear Sky B exact',zh['block.skyboxblock.syferfontein_sky_block']=='天空盒方块（晴空·B）' and en['block.skyboxblock.syferfontein_sky_block']=='Sky Box Block (Clear Sky B)')
        need('nine stable block identities',set(k for k in zh if k.startswith('block.'))==set(k for k in json.loads(old.read('assets/skyboxblock/lang/zh_cn.json')) if k.startswith('block.')))
        need('no old display transliterations',not any(s in '\n'.join(zh.values()) for s in ['克洛彭海姆','赛弗方丹']))
        need('no validation probe bundled',not any(n.startswith(('dev/yg/skyboxprobe/','dev/yg/skyboxfixture/')) for n in names))
        need('notices retained',all(n in names for n in ['AUTHORS.md','THIRD_PARTY_NOTICES.md','LICENSE_skyboxblock']))
        for path in (ROOT/'src/main/resources').rglob('*'):
            if not path.is_file():continue
            name=path.relative_to(ROOT/'src/main/resources').as_posix()
            if name=='fabric.mod.json':continue
            if name=='skyboxblock.mixins.json':need('mixin declaration identical to source',json.loads(jar.read(name))==json.loads(path.read_text(encoding='utf-8')))
            else:need('source resource '+name,name in names and jar.read(name)==path.read_bytes())
        for name in sorted(names):
            if name.endswith('.png'):
                need('image byte preserved '+name,name in old.namelist() and jar.read(name)==old.read(name))
            if name.startswith('assets/skyboxblock/shaders/core/vanilla_'):
                need('vanilla shader byte preserved '+name,jar.read(name)==old.read(name))
            if name.endswith('.class') and not any(x in name for x in ['client/SkyWorldRenderer','client/SkyRenderMath','client/RenderRegression']):
                need('other production class byte preserved '+name,name in old.namelist() and jar.read(name)==old.read(name))
        functions=[]
        for name in ['world_sky','sky_resolve','sky_material_background']:
            path='assets/skyboxblock/shaders/core/'+name+'.fsh';before=old.read(path).decode('utf-8');after=jar.read(path).decode('utf-8')
            bfun,bend=block(before,before.index('vec3 skyColor'))
            afun,aend=block(after,after.index('vec3 skyColor'))
            functions.append(afun)
            def without_panorama(fun):
                start=fun.index('if (SkyStyle == 3)');branch,end=block(fun,start);return fun[:start]+fun[end:]
            need(name+' vanilla/solid branch byte preserved',without_panorama(bfun)==without_panorama(afun))
            before_rest=before[:before.index('vec3 skyColor')]+before[bend:]
            after_rest=after[:after.index('vec3 skyColor')]+after[aend:]
            after_rest=after_rest.replace('uniform mat4 PanoramaRayInverse;\n','').replace('uniform int PanoramaLegacyRays;\n','')
            need(name+' original depth/HDR/main body byte preserved',before_rest==after_rest)
            configuration=json.loads(jar.read('assets/skyboxblock/shaders/core/'+name+'.json'));uniforms={u['name']:u for u in configuration['uniforms']}
            need(name+' panorama inverse declared',uniforms['PanoramaRayInverse']['count']==16 and uniforms['PanoramaLegacyRays']['values']==[0])
            need(name+' sampler count unchanged',configuration['samplers']==json.loads(old.read('assets/skyboxblock/shaders/core/'+name+'.json'))['samplers'])
        need('same panorama implementation in all color stages',len(set(functions))==1)
        need('unchanged world geometry vertex shader',jar.read('assets/skyboxblock/shaders/core/world_sky.vsh')==old.read('assets/skyboxblock/shaders/core/world_sky.vsh'))
        for name in sorted(n for n in names if n.startswith(('data/skyboxblock/','data/minecraft/tags/','assets/skyboxblock/blockstates/','assets/skyboxblock/models/'))):
            need('existing registration data '+name,jar.read(name)==old.read(name))
    math=(ROOT/'build/reports/render-math-regression.txt').read_text(encoding='utf-8');need('389385 mathematical assertions','PASS: 389385 assertions' in math)
    generator=(ROOT/'tools/prepare-r31-materials.py').read_text(encoding='utf-8');need('generator keeps A/B names',"'晴空·A', 'Clear Sky A'" in generator and "'晴空·B', 'Clear Sky B'" in generator)
    sources=ROOT/f'build/libs/skyboxblock-{VERSION}-sources.jar';need('source JAR present and valid',zipfile.is_zipfile(sources))
    result=dict(version=VERSION,jarSha256=sha(JAR),jarBytes=JAR.stat().st_size,checks=len(checks),passedChecks=sum(c['passed'] for c in checks),failedChecks=sum(not c['passed'] for c in checks),checksDetail=checks)
    target=ROOT/'build/reports/r32-package-audit.json';target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='checksDetail'},indent=2))
    if result['failedChecks']:print(json.dumps([c for c in checks if not c['passed']],ensure_ascii=False,indent=2))
    return 0 if result['failedChecks']==0 else 1
if __name__=='__main__':raise SystemExit(main())
