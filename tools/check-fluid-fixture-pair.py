#!/usr/bin/env python3
"""Real same-jar water-only versus old world-freeze evidence; no threshold changes."""
from __future__ import annotations
import argparse,hashlib,json,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('water_only',type=Path);p.add_argument('global_negative',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args();checks=[];pins={};observations=[]
 def pin(path):
  path=path.resolve(strict=True);path.relative_to(ROOT);pins[str(path)]=hashlib.sha256(path.read_bytes()).hexdigest();return path
 def read(path):return json.loads(pin(path).read_text(encoding='utf-8'))
 def add(name,passed,**data):checks.append(dict(name=name,passed=bool(passed),**data))
 try:
  roots=[a.water_only.resolve(strict=True),a.global_negative.resolve(strict=True)];fs=[read(root/'fixture.json') for root in roots]
  add('distinct-owned-runtime-roots',roots[0]!=roots[1]);add('same-frozen-jar-and-shader-options',fs[0]['jarSha256']==fs[1]['jarSha256'] and fs[0]['shaderOptions']==fs[1]['shaderOptions'])
  for i,(root,f) in enumerate(zip(roots,fs)):
   run=read(root/'run-result.json');add(str(i)+':finished-runtime',run.get('exitCode')==0 and run.get('finished') is True and run.get('fatalErrors')==[] and run.get('renderThreadViolations')==[])
   add(str(i)+':actual-mode',f.get('negativeGlobalFluidFreeze') is bool(i) and f.get('nativeTransparencyControl') is True)
   jar=pin(Path(f['jar']));actual=pins[str(jar)];add(str(i)+':frozen-hash',actual==f['jarSha256'])
   pin(root/'client.log');mods={}
   for installed in sorted((root/'mods').glob('*.jar')):
    installed=pin(installed)
    with zipfile.ZipFile(installed) as z:info=json.loads(z.read('fabric.mod.json')) if 'fabric.mod.json' in z.namelist() else {'id':installed.name}
    if info['id'] in mods:raise ValueError('Duplicate installed mod identity')
    mods[info['id']]=pins[str(installed)]
   add(str(i)+':actual-installed-main-matches',mods.get('skyboxblock')==actual)
   if i:add('same-actual-supporting-mods',mods==observations[0]['actualMods'])
   pack=pin(root/'shaderpacks'/f['shader']);pack_hash=pins[str(pack)]
   if i:add('same-actual-shader-archive',pack_hash==observations[0]['actualShaderSha256'])
   times=[];client_counts=[];water_counts=[];rows=[]
   for name in f['requestedScenes']:
    m=read(root/'results'/(name+'.json'));t=m['transparency'];s,c=t['actualServerGeometry'],t['actualClientGeometry'];times.append(t['actualServerGameTime']);client_counts.append(c['existingChunks']);water_counts.append(m['suppressedFixtureWaterTicks'])
    rows.append(dict(scene=name,serverGameTime=times[-1],clientGameTime=t['actualClientGameTime'],clientGeometry=c,serverGeometry=s,suppressedWaterTicks=water_counts[-1]))
    if not i:
     add(name+':client-retains-all-four-chunks',c['existingChunks']==4)
     add(name+':physical-state-server-client-equal',s==c)
     add(name+':no-water-outside-fixed-wall',c['waterOutside']==s['waterOutside']==0)
     if t['front']=='water':add(name+':actual-water-wall-and-control',c['water']==40 and water_counts[-1]>0)
    add(str(i)+':'+name+':never-sky-private-path-absent',m['blocks']==m['vertices']==m['depthChangedPixels']==0 and not m['itrpMaterialWrites'] and not m['itrpBackgroundInjected'] and not m['finalResolved'] and m.get('neverSkyObservedRootFrames',0)>0)
   if not i:add('water-only-real-server-clock-advances',max(times)>min(times))
   else:add('old-world-freeze-really-stops-server-clock',max(times)==min(times));add('old-global-mode-does-not-suppress-scheduled-fluid-hook',max(water_counts)==0)
   observations.append(dict(mode='water-only' if not i else 'global-negative',times=times,clientChunkCounts=client_counts,rows=rows,actualMods=mods,actualShaderSha256=pack_hash))
  error=None
 except (OSError,ValueError,KeyError,TypeError) as exc:error=str(exc);add('complete-actual-pair',False,error=error)
 report=dict(kind='owned-water-scheduling-causal-pair',passed=bool(checks) and all(c['passed'] for c in checks),passedChecks=sum(c['passed'] for c in checks),totalChecks=len(checks),checks=checks,observations=observations,inputs=pins,error=error,
  note='Global-freeze chunk loss is phase-dependent, not forced or required to reproduce in every run. Actual counts and times remain observations; this proves narrow fixture scheduling and does not prove unique IP packet history.')
 a.output.parent.mkdir(parents=True,exist_ok=True)
 with a.output.open('x',encoding='utf-8') as out:json.dump(report,out,indent=2,allow_nan=False)
 print(f"{report['passedChecks']}/{report['totalChecks']} {'PASS' if report['passed'] else 'FAIL'}",a.output)
 for c in checks:
  if not c['passed']:print('FAIL',json.dumps(c))
 for o in observations:print(o['mode'],'times',o['times'],'client chunks',o['clientChunkCounts'])
 return 0 if report['passed'] else 1
if __name__=='__main__':raise SystemExit(main())
