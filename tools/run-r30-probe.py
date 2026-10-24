#!/usr/bin/env python3
"""Run a separate owned DH/performance probe against an explicit frozen production JAR.

No user saves, configuration, installed mods or processes are changed. Execution is
not acceptance. The probe JAR is a distinct mod and is never put into the main JAR.
"""
from __future__ import annotations
import argparse, ctypes, hashlib, importlib.util, json, os, re, shutil, subprocess, time, zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('r30_render_base',ROOT/'tools/render-regression.py')
base=importlib.util.module_from_spec(spec); spec.loader.exec_module(base)
launch=base.launcher
FROZEN='07976170c7e8ca8322830091adf27a28f6df33dbcffe7eedf11c138eaad3b51c'

def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def write_new(path,value):
    with Path(path).open('x',encoding='utf-8') as stream: json.dump(value,stream,indent=2,ensure_ascii=False); stream.write('\n')

def phases(mode,warmup,sample,cycles,hold):
    def phase(name,w=0,h=0,**extra):
        return dict(name=name,panelWidth=w,panelHeight=h,warmupSeconds=warmup,sampleSeconds=sample,**extra)
    if mode=='smoke': return [phase('no-sky'),phase('panel-49',7,7),phase('removed')]
    if mode=='dynamic':
        return [phase('lod-generation-no-sky',warmupSecondsOverride=90),
                phase('near-sky',7,7),
                phase('near-glass',7,7,commands=['fill -4 136 -1 4 148 -1 minecraft:glass']),
                phase('remove-glass',7,7,commands=['fill -4 136 -1 4 148 -1 minecraft:air']),
                phase('move-east',x=512.5,preservePanel=True),
                phase('move-far',x=1024.5,preservePanel=True),
                phase('return-origin',preservePanel=True),
                phase('large-near-sky',64,64),
                phase('distant-world-update',7,7,commands=['forceload add -48 320 48 320','fill -48 110 320 48 190 320 minecraft:gold_block']),
                phase('distant-world-remove',7,7,commands=['fill -48 110 320 48 190 320 minecraft:air','forceload remove -48 320 48 320']),
                phase('resource-reload',7,7,reload=True),
                phase('resize-small',7,7,width=1024,height=768),
                phase('resize-return-no-sky',width=1280,height=800)]
    result=[phase('baseline-start'),phase('panel-49',7,7),phase('panel-256',16,16),phase('panel-1024',32,32),phase('panel-4096',64,64),phase('baseline-after-scale')]
    for index in range(cycles):
        result += [phase(f'cycle-{index:02d}-reload',32,32,reload=True),
                   phase(f'cycle-{index:02d}-resize',32,32,width=1024,height=768),
                   phase(f'cycle-{index:02d}-restore',32,32,width=1280,height=800),
                   phase(f'cycle-{index:02d}-removed')]
    result += [phase('long-hold-4096',64,64,sampleSecondsOverride=hold),phase('baseline-final')]
    return result

class MemoryCounter(ctypes.Structure):
    _fields_=[('cb',ctypes.c_ulong),('PageFaultCount',ctypes.c_ulong)]+[(name,ctypes.c_size_t) for name in ('PeakWorkingSetSize','WorkingSetSize','QuotaPeakPagedPoolUsage','QuotaPagedPoolUsage','QuotaPeakNonPagedPoolUsage','QuotaNonPagedPoolUsage','PagefileUsage','PeakPagefileUsage','PrivateUsage')]

class ProcessMemory:
    def __init__(self,pid):
        self.handle=None
        if os.name=='nt':
            kernel=ctypes.WinDLL('kernel32',use_last_error=True); self.kernel=kernel
            kernel.OpenProcess.argtypes=[ctypes.c_ulong,ctypes.c_bool,ctypes.c_ulong]; kernel.OpenProcess.restype=ctypes.c_void_p
            kernel.CloseHandle.argtypes=[ctypes.c_void_p]; kernel.CloseHandle.restype=ctypes.c_bool
            self.handle=kernel.OpenProcess(0x1000|0x10,False,pid)
            self.api=ctypes.WinDLL('psapi',use_last_error=True).GetProcessMemoryInfo
            self.api.argtypes=[ctypes.c_void_p,ctypes.POINTER(MemoryCounter),ctypes.c_ulong]; self.api.restype=ctypes.c_bool
    def sample(self):
        if not self.handle:return dict(status='UNAVAILABLE',winError=ctypes.get_last_error() if os.name=='nt' else None)
        data=MemoryCounter(); data.cb=ctypes.sizeof(data)
        if not self.api(self.handle,ctypes.byref(data),data.cb):return dict(status='UNAVAILABLE',winError=ctypes.get_last_error())
        return dict(status='SAMPLED',workingSetBytes=data.WorkingSetSize,privateBytes=data.PrivateUsage,peakWorkingSetBytes=data.PeakWorkingSetSize,pageFaults=data.PageFaultCount)
    def close(self):
        if self.handle:self.kernel.CloseHandle(self.handle); self.handle=None

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--profile',choices=['vanilla','iris','dh','iris-dh'],default='dh')
    p.add_argument('--mode',choices=['smoke','dynamic','performance'],default='dynamic')
    p.add_argument('--label',required=True)
    p.add_argument('--jar',type=Path,default=ROOT/'build/libs/skyboxblock-1.0.2-test.8.jar')
    p.add_argument('--probe',type=Path,default=ROOT/'build/libs/skybox-validation-probe-r30.2.jar')
    p.add_argument('--edge-stages',action='store_true',help='Owned R34 diagnostic stage and coverage readback only')
    p.add_argument('--shader'); p.add_argument('--fsr',type=int,default=-1)
    p.add_argument('--terrain',choices=['flat','normal'],default='normal')
    p.add_argument('--warmup',type=float,default=45); p.add_argument('--sample',type=float,default=30)
    p.add_argument('--cycles',type=int,default=5); p.add_argument('--hold-seconds',type=float,default=1200)
    p.add_argument('--lod-radius',type=int,default=64); p.add_argument('--dh-threads',type=int,default=4)
    p.add_argument('--render-distance',type=int,default=8); p.add_argument('--max-fps',type=int,default=260,
                   help='Minecraft 1.21.1 accepts 10..260; zero is rejected, not an unlimited setting')
    p.add_argument('--heap',default='6G'); p.add_argument('--timeout',type=float)
    p.add_argument('--never-sky',action='store_true',help='Independent control: every panel request uses physical air; production mod remains loaded')
    p.add_argument('--gc',choices=['g1','zgc'],default='g1',help='Explicit owned-fixture collector; use the same collector within comparisons')
    p.add_argument('--jfr',action='store_true',help='Owned allocation-profile run only; do not compare its frame times to unprofiled runs')
    a=p.parse_args(argv)
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]+',a.label):p.error('Unique lowercase label required')
    if a.shader and a.profile not in {'iris','iris-dh'}:p.error('Shader requires an Iris profile')
    if a.mode=='dynamic' and a.profile not in {'dh','iris-dh'}:p.error('Dynamic DH requires actual DH')
    if min(a.warmup,a.sample,a.hold_seconds)<=0 or not 0<=a.cycles<=50:p.error('Positive measurement durations and bounded cycles required')
    if not 16<=a.lod_radius<=256 or not 1<=a.dh_threads<=16 or not 2<=a.render_distance<=32:p.error('Invalid explicit rendering bounds')
    if not re.fullmatch(r'[1-9][0-9]*[MG]',a.heap,re.I) or not 10<=a.max_fps<=260:p.error('Invalid heap or FPS cap (10..260)')
    jar=a.jar.resolve(strict=True); probe=a.probe.resolve(strict=True)
    with zipfile.ZipFile(probe) as z:assert json.loads(z.read('fabric.mod.json'))['id']=='skybox_r30_probe'
    plan_phases=phases(a.mode,a.warmup,a.sample,a.cycles,a.hold_seconds)
    for stage in plan_phases:
        for key in ('warmupSeconds','sampleSeconds'):
            if key+'Override' in stage:stage[key]=stage.pop(key+'Override')
    if a.never_sky:
        for stage in plan_phases:stage['material']='air'
    duration=sum(stage['warmupSeconds']+stage['sampleSeconds'] for stage in plan_phases)
    timeout=a.timeout or duration+420
    if timeout<duration:p.error('Timeout shorter than declared measurement duration')
    instance=ROOT/'build/render-regression'/a.label; instance.mkdir(parents=True,exist_ok=False)
    selected=base.prepare_mods(instance,a.profile,jar); shutil.copy2(probe,instance/'mods'/probe.name)
    assert not any(m['id']=='immersive_portals' for m in selected)
    config=instance/'config'; config.mkdir()
    (instance/'options.txt').write_text('version:3955\nenableVsync:false\nmaxFps:'+str(a.max_fps)+'\npauseOnLostFocus:false\nrenderDistance:'+str(a.render_distance)+'\nsimulationDistance:5\nguiScale:2\nlang:en_us\nbobView:false\nrenderClouds:"false"\nresourcePacks:[]\nonboardAccessibility:false\ntutorialStep:none\n',encoding='utf-8')
    dh_config={}
    if a.profile in {'dh','iris-dh'}:
        source=launch.VERSION_DIR/'config/DistantHorizons.toml'
        original=source.read_text(encoding='utf-8'); updated=original.replace('enableAutoUpdater = true','enableAutoUpdater = false')
        for key,value in [('lodChunkRenderDistanceRadius',str(a.lod_radius)),('numberOfThreads',str(a.dh_threads))]:
            updated,n=re.subn(r'(?m)^(\s*'+key+r'\s*=\s*).+$',lambda m:m[1]+value,updated)
            assert n==1,(key,n)
        import tomllib
        parsed=tomllib.loads(updated)
        assert parsed['common']['worldGenerator']['generatorPlan']!='DISABLED'
        assert parsed['client']['advanced']['debugging']['rendererMode']=='DEFAULT'
        (config/'DistantHorizons.toml').write_text(updated,encoding='utf-8')
        dh_config=dict(sourceSha256=hashlib.sha256(original.encode()).hexdigest(),preparedSha256=hashlib.sha256(updated.encode()).hexdigest(),actual=parsed)
    (config/'iris.properties').write_text('enableShaders='+str(bool(a.shader)).lower()+'\ndisableUpdateMessage=true\n'+('shaderPack='+a.shader+'\n' if a.shader else ''),encoding='utf-8')
    if a.shader:
        directory=instance/'shaderpacks'; directory.mkdir(); source=launch.VERSION_DIR/'shaderpacks'/a.shader
        shutil.copy2(source,directory/source.name)
        settings=launch.VERSION_DIR/'shaderpacks'/(a.shader+'.txt')
        text=settings.read_text(encoding='utf-8') if settings.exists() else ''
        lines=[line for line in text.splitlines() if line.partition('=')[0].strip() not in {'FSR2_SCALE','MOTION_BLUR'}]
        (directory/(a.shader+'.txt')).write_text('\n'.join(lines+[f'FSR2_SCALE={a.fsr}','MOTION_BLUR=false'])+'\n',encoding='utf-8')
    base.make_world(instance/'saves/fixture',terrain=a.terrain)
    version=json.loads((launch.VERSION_DIR/(launch.VERSION_NAME+'.json')).read_text(encoding='utf-8'))
    classpath=launch.build_classpath(version); assert all(Path(path).exists() for path in classpath)
    pinned={str(path.relative_to(instance)):sha(path) for directory in ['mods','shaderpacks'] for path in (instance/directory).glob('*.jar' if directory=='mods' else '*.zip')}
    marker=dict(schemaVersion=1,owned=True,gameDir=str(instance.resolve()),mode=a.mode,profile=a.profile,terrain=a.terrain,
                productionJarSha256=sha(jar),frozenTest8Unchanged=sha(jar)==FROZEN,probeJarSha256=sha(probe),actualSelectedMods=selected,
                liveWorldTicks=True,freezeShaderTime=False,freezeClouds=False,freezeWater=False,phases=plan_phases,
                dhConfiguration=dh_config,maxFps=a.max_fps,renderDistance=a.render_distance,heap=a.heap,gc=a.gc.upper(),
                neverSkyControl=a.never_sky,allocationProfiling=a.jfr,
                edgeStageReadback=a.edge_stages,
                scope='owned new world only; snapshots excluded from measured phase intervals; 1Hz diagnostic sampling shared across all compared phases',inputs=pinned)
    write_new(instance/'r30-owned.json',marker)
    args=[str(launch.JAVA),'-Xms1G','-Xmx'+a.heap,
          *(['-XX:+UseZGC','-XX:+ZGenerational'] if a.gc=='zgc' else ['-XX:+UseG1GC']),
          *(['-XX:StartFlightRecording=filename=r30-allocation.jfr,settings=profile,dumponexit=true,maxsize=128m'] if a.jfr else []),
          f'-Djava.library.path={launch.NATIVES}',
          f'-Dorg.lwjgl.system.SharedLibraryExtractPath={launch.NATIVES}',
          '-Dskyboxblock.regression=false','-Dskyboxblock.debug=false','-Dskyboxblock.r30.enabled=true',
          '-Dskyboxblock.r34.edgeStages='+str(a.edge_stages).lower(),
          '-Dskyboxblock.r30.stateAudit='+str(a.mode!='performance').lower(),
          f'-Dskyboxblock.r30.ownedGameDir={instance.resolve()}','-cp',os.pathsep.join(classpath),
          'net.fabricmc.loader.impl.launch.knot.KnotClient','--username','SkyboxR30','--version','1.21.1',
          '--gameDir',str(instance),'--assetsDir',str(launch.MC_ROOT/'assets'),'--assetIndex','17',
          '--uuid','00000000000000000000000000000000','--accessToken','0','--userType','legacy',
          '--versionType','release','--width','1280','--height','800','--quickPlaySingleplayer','fixture']
    write_new(instance/'r30-command.json',args)
    print('fixture:',instance,flush=True); started=time.monotonic(); timed_out=False
    with (instance/'client.log').open('xb') as log,(instance/'process-memory.jsonl').open('x',encoding='utf-8') as monitor:
        process=subprocess.Popen(args,cwd=instance,stdout=log,stderr=subprocess.STDOUT)
        write_new(instance/'owned-process.json',dict(pid=process.pid,executable=str(launch.JAVA),gameDir=str(instance),timeoutSeconds=timeout))
        print('owned PID:',process.pid,flush=True); memory=ProcessMemory(process.pid)
        try:
            while process.poll() is None:
                monitor.write(json.dumps(dict(elapsedSeconds=time.monotonic()-started,**memory.sample()))+'\n'); monitor.flush()
                if time.monotonic()-started>timeout:
                    timed_out=True; process.terminate(); process.wait(timeout=30); break
                time.sleep(1)
        finally:memory.close()
    text=(instance/'client.log').read_text(encoding='utf-8',errors='replace')
    summary=instance/'r30-results/run-summary.json'
    actual=json.loads(summary.read_text(encoding='utf-8')) if summary.exists() else {}
    errors=[line for line in text.splitlines() if '/ERROR]' in line or '[r30-probe] FAILED' in line]
    fatal=[line for line in text.splitlines() if any(k in line for k in ('OutOfMemoryError','EXCEPTION_ACCESS_VIOLATION','Rendersystem called from wrong thread','Encountered an unexpected exception','Failed to compile shader'))]
    stable={key:sha(instance/key)==value for key,value in pinned.items()}
    result=dict(exitCode=process.returncode,seconds=round(time.monotonic()-started,3),timedOut=timed_out,complete=actual.get('complete',False),
                phases=len(actual.get('phases',[])),expectedPhases=len(plan_phases),errors=errors,fatalErrors=fatal,
                inputBytesUnchanged=all(stable.values()),inputChecks=stable,productionJarSha256=sha(jar),probeJarSha256=sha(probe),
                dynamicDhAccepted=False,performanceAccepted=False,releaseAccepted=False)
    write_new(instance/'run-result.json',result); print(json.dumps(result,indent=2),flush=True)
    return 0 if process.returncode==0 and not timed_out and result['complete'] and result['phases']==len(plan_phases) and not errors and not fatal and all(stable.values()) else 1

if __name__=='__main__':raise SystemExit(main())
