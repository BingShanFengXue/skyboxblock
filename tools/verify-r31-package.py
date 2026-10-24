#!/usr/bin/env python3
"""R31 actual binary/resource/provenance audit, independent of old single-block package rules."""
from pathlib import Path
import hashlib
import json
import re
import zipfile
import numpy as np
from PIL import Image
import importlib.util

ROOT=Path(__file__).resolve().parents[1]
JAR=ROOT/'build/libs/skyboxblock-1.0.3-test.1-r31.jar'
RES=ROOT/'src/main/resources'
checks=[]
def need(name,condition,**detail):checks.append(dict(name=name,passed=bool(condition),**detail))
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def read(path):return path.read_text(encoding='utf-8')
def main():
    spec=importlib.util.spec_from_file_location('materials',ROOT/'tools/prepare-r31-materials.py');materials=importlib.util.module_from_spec(spec);spec.loader.exec_module(materials)
    styles=['sky_block']+[row[1] for row in materials.STYLES]
    with zipfile.ZipFile(JAR) as jar:
        names=set(jar.namelist());meta=json.loads(jar.read('fabric.mod.json'))
        need('version',meta['version']=='1.0.3-test.1-r31',actual=meta['version'])
        need('author preserved',meta['authors']==['Bing6512'])
        need('common and client entrypoints',meta['environment']=='*' and len(meta['entrypoints']['main'])==1 and len(meta['entrypoints']['client'])==1)
        need('no test probe or fixture bundled',not any(n.startswith(('dev/yg/skyboxprobe/','dev/yg/skyboxfixture/')) for n in names))
        need('style enum bundled','dev/yg/skyboxblock/SkyStyle.class' in names)
        need('all required notices',all(n in names for n in ['AUTHORS.md','THIRD_PARTY_NOTICES.md','LICENSE_skyboxblock']))
        need('R31 asset notice is in binary','R31 / 1.0.3' in jar.read('THIRD_PARTY_NOTICES.md').decode('utf-8'))
        zh=json.loads(jar.read('assets/skyboxblock/lang/zh_cn.json'));en=json.loads(jar.read('assets/skyboxblock/lang/en_us.json'))
        need('original renamed',zh['block.skyboxblock.sky_block']=='天空盒方块（原版）')
        need('creative tab renamed',zh['itemGroup.skyboxblock.sky']=='天空盒方块')
        need('white exact name',zh['block.skyboxblock.white_sky_block']=='天空盒方块（纯白）')
        need('black exact name',zh['block.skyboxblock.black_sky_block']=='天空盒方块（纯黑）')
        tags=json.loads(jar.read('data/minecraft/tags/block/mineable/pickaxe.json'))
        need('all nine mining tags',set(tags['values'])=={'skyboxblock:'+s for s in styles})
        for block in styles:
            key='block.skyboxblock.'+block
            need(block+' bilingual',key in zh and key in en and '测试' not in zh[key])
            for prefix in ['assets/skyboxblock/blockstates/','assets/skyboxblock/models/block/','assets/skyboxblock/models/item/','data/skyboxblock/loot_table/blocks/','data/skyboxblock/recipe/']:
                name=prefix+block+'.json';need(name+' present',name in names)
                if name in names:json.loads(jar.read(name))
            need(block+' icon','assets/skyboxblock/textures/block/'+block+'.png' in names)
            drop=json.loads(jar.read('data/skyboxblock/loot_table/blocks/'+block+'.json'))
            need(block+' self drop',drop['pools'][0]['entries'][0]['name']=='skyboxblock:'+block)
        for path in RES.rglob('*'):
            if not path.is_file():continue
            name=path.relative_to(RES).as_posix()
            if name=='fabric.mod.json':continue
            if name=='skyboxblock.mixins.json':
                need('binary mixin configuration semantic equality',name in names and json.loads(jar.read(name))==json.loads(read(path)))
                continue
            need('binary resource exact '+name,name in names and jar.read(name)==path.read_bytes())
        functions=[]
        for name in ['world_sky','sky_resolve','sky_material_background']:
            frag=jar.read('assets/skyboxblock/shaders/core/'+name+'.fsh').decode('utf-8')
            conf=json.loads(jar.read('assets/skyboxblock/shaders/core/'+name+'.json'))
            functions.append(frag[frag.index('vec3 skyColor'):frag.index('\n}',frag.index('vec3 skyColor'))+2])
            need(name+' style uniform',any(u['name']=='SkyStyle' for u in conf['uniforms']))
            need(name+' bounded sampler count',len(conf['samplers'])<=5)
        need('same spherical formula in all color passes',len(set(functions))==1)
        need('source cube files not bundled',not any('ApocalypseLand' in n for n in names))
    asset=json.loads(read(ROOT/'build/reports/r31-material-assets.json'))
    source=Path(asset['sourceDirectory'])
    for material in asset['materials']:
        style=material['style']
        if style in ('white','black'):continue
        path=RES/'assets/skyboxblock/textures/sky'/f'{style}.png';a=np.array(Image.open(path).convert('RGB'))
        need(style+' output identity',sha(path)==material['outputSha256'])
        need(style+' longitude exact',np.array_equal(a[:,0],a[:,-1]))
        need(style+' north pole exact',np.ptp(a[0].astype(int),axis=0).max()==0)
        need(style+' south pole exact',np.ptp(a[-1].astype(int),axis=0).max()==0)
        if 'source' in material:need(style+' original unchanged',sha(source/material['source'])==material['sourceSha256'])
        for item in material.get('sources',[]):need(item['path']+' original unchanged',sha(source/item['path'])==item['sha256'])
    need('all six panoramas',sum('outputSha256' in x for x in asset['materials'])==6)
    for style,value in [('white',255),('black',0)]:
        a=np.array(Image.open(RES/'assets/skyboxblock/textures/block'/f'{style}_sky_block.png').convert('RGB'))
        need(style+' pure icon',np.all(a==value))
    math=read(ROOT/'build/reports/render-math-regression.txt')
    need('render math regression', 'PASS: 339384 assertions' in math)
    for jarpath in [JAR,ROOT/'build/libs/skyboxblock-1.0.3-test.1-r31-sources.jar']:
        need(jarpath.name+' valid ZIP',zipfile.is_zipfile(jarpath))
    result=dict(jar=str(JAR),sha256=sha(JAR),bytes=JAR.stat().st_size,checks=len(checks),passed=sum(x['passed'] for x in checks),failed=sum(not x['passed'] for x in checks),results=checks)
    target=ROOT/'build/reports/r31-package-audit.json';target.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='results'},indent=2))
    if result['failed']:print(json.dumps([x for x in checks if not x['passed']],ensure_ascii=False,indent=2))
    return 1 if result['failed'] else 0
if __name__=='__main__':raise SystemExit(main())
