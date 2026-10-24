#!/usr/bin/env python3
"""TEMP-only profile, generated-world and benchmark-plan tests, not GPU acceptance."""
from __future__ import annotations
import gzip, importlib.util, io, json, os, struct, tempfile, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('r30_runner',ROOT/'tools/run-r30-probe.py')
runner=importlib.util.module_from_spec(spec); spec.loader.exec_module(runner)
checks=[]
def need(name,condition):
    checks.append(dict(name=name,passed=bool(condition)))
    if not condition:raise AssertionError(name)
def text(stream):
    size=struct.unpack('>H',stream.read(2))[0]; return stream.read(size).decode('utf-8')
def payload(stream,kind):
    if kind in {1,2,3,4,5,6}:
        fmt={1:'>b',2:'>h',3:'>i',4:'>q',5:'>f',6:'>d'}[kind]; return struct.unpack(fmt,stream.read(struct.calcsize(fmt)))[0]
    if kind==8:return text(stream)
    if kind==9:
        subtype=stream.read(1)[0]; count=struct.unpack('>i',stream.read(4))[0]
        return [payload(stream,subtype) for _ in range(count)]
    if kind==10:
        out={}
        while True:
            subtype=stream.read(1)[0]
            if subtype==0:return out
            key=text(stream); out[key]=payload(stream,subtype)
    if kind==11:
        count=struct.unpack('>i',stream.read(4))[0]; return [struct.unpack('>i',stream.read(4))[0] for _ in range(count)]
    raise ValueError(kind)
def world(path):
    stream=io.BytesIO(gzip.decompress(path.read_bytes())); need('NBT-root-compound',stream.read(1)==b'\x0a'); text(stream)
    result=payload(stream,10); need('NBT-no-trailing-payload',stream.read()==b''); return result['Data']
def jar(path,identifier,version='1'):
    with zipfile.ZipFile(path,'x') as z:z.writestr('fabric.mod.json',json.dumps(dict(schemaVersion=1,id=identifier,version=version)))

def main():
    with tempfile.TemporaryDirectory(prefix='skybox-r30-selftest-') as raw:
        root=Path(raw); reference=root/'reference'; (reference/'mods').mkdir(parents=True)
        for name in ('fabric-api','iris','sodium','distanthorizons','immersive_portals','skyboxblock','unrelated'):
            jar(reference/'mods'/(name+'.jar'),name)
        frozen=root/'candidate.jar'; jar(frozen,'skyboxblock','FROZEN')
        previous=runner.base.launcher.VERSION_DIR
        try:
            runner.base.launcher.VERSION_DIR=reference
            for profile,expected in [
                ('vanilla',{'fabric-api'}),('iris',{'fabric-api','iris','sodium'}),
                ('dh',{'fabric-api','distanthorizons'}),('iris-dh',{'fabric-api','iris','sodium','distanthorizons'}),
                ('compat',{'fabric-api','iris','sodium','distanthorizons','immersive_portals'}),
                ('full',{'fabric-api','iris','sodium','distanthorizons','immersive_portals','unrelated'})]:
                owned=root/profile; owned.mkdir(); before=runner.sha(frozen)
                actual=runner.base.prepare_mods(owned,profile,frozen)
                need(profile+'-exact-supporting-mods',{m['id'] for m in actual}==expected)
                need(profile+'-candidate-byte-copy',runner.sha(owned/'mods/candidate.jar')==before)
                need(profile+'-one-actual-main-ID',sum(json.loads(zipfile.ZipFile(p).read('fabric.mod.json'))['id']=='skyboxblock' for p in (owned/'mods').glob('*.jar'))==1)
                if profile in {'dh','iris-dh'}:need(profile+'-portals-absent',not any(m['id']=='immersive_portals' for m in actual))
        finally:runner.base.launcher.VERSION_DIR=previous
        for terrain in ('flat','normal'):
            path=root/('world-'+terrain); runner.base.make_world(path,terrain=terrain); data=world(path/'level.dat')
            need(terrain+'-owned-world-name',data['LevelName']=='Skybox render fixture')
            dimensions=data['WorldGenSettings']['dimensions']; generator=dimensions['minecraft:overworld']['generator']
            need(terrain+'-generator-type',generator['type']==('minecraft:flat' if terrain=='flat' else 'minecraft:noise'))
            if terrain=='normal':
                need('normal-overworld-noise',generator['settings']=='minecraft:overworld')
                need('normal-overworld-biome-preset',generator['biome_source']['preset']=='minecraft:overworld')
            need(terrain+'-nether-preset-preserved',dimensions['minecraft:the_nether']['generator']['biome_source']['preset']=='minecraft:nether')
            need(terrain+'-end-generator-preserved',dimensions['minecraft:the_end']['generator']['settings']=='minecraft:end')
        try:runner.base.make_world(root/'invalid',terrain='INVALID')
        except ValueError:need('invalid-terrain-rejected-before-writing',not (root/'invalid').exists())
        else:raise AssertionError('Invalid terrain accepted')
        for mode in ('smoke','dynamic','performance'):
            stages=runner.phases(mode,15,30,3,1200)
            need(mode+'-unique-phase-identities',len({x['name'] for x in stages})==len(stages))
            need(mode+'-positive-duration',all(x['sampleSeconds']>0 and x['warmupSeconds']>0 for x in stages))
            if mode=='performance':
                need('performance-4096-actual-request',any(x['panelWidth']*x['panelHeight']==4096 for x in stages))
                need('performance-three-resource-reload-cycles',sum(x.get('reload',False) for x in stages)==3)
                need('performance-long-hold-explicit',next(x for x in stages if x['name']=='long-hold-4096')['sampleSecondsOverride']==1200)
            if mode=='dynamic':need('dynamic-retains-same-panel-during-travel',sum(x.get('preservePanel',False) for x in stages)==3)
        memory=runner.ProcessMemory(os.getpid())
        try:
            sample=memory.sample(); need('self-owned-process-memory-positive',sample['status']=='SAMPLED' and sample['workingSetBytes']>0 and sample['privateBytes']>0)
        finally:memory.close()
    result=dict(status='PASS',passed=sum(x['passed'] for x in checks),total=len(checks),checks=checks,gpuAcceptance=False)
    print(json.dumps(result,indent=2)); return 0
if __name__=='__main__':raise SystemExit(main())
