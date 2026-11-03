#!/usr/bin/env python3
"""Run final-jar cases sequentially, close every owned client, preserve all outputs."""
from pathlib import Path
import argparse
import contextlib
import ctypes
from ctypes import wintypes
import importlib.util
import io
import json
import subprocess
import sys

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('runtime',HERE/'runtime-regression.py')
runtime=importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)
PROJECT=runtime.PROJECT
PYTHON=sys.executable


def run(case,label,script,*arguments):
    print('ACCEPTANCE',case,label,flush=True)
    path=runtime.paths(case)[0]/(label+'.log')
    with path.open('wb') as output:
        result=subprocess.run([PYTHON,str(HERE/script),*arguments],cwd=PROJECT,stdout=output,stderr=subprocess.STDOUT)
    text=path.read_text(encoding='utf-8',errors='replace')
    print('\n'.join(text.splitlines()[-12:]),flush=True)
    if result.returncode:raise RuntimeError(str(path)+' failed, exit='+str(result.returncode))


def stop(name):
    case,game,control=runtime.paths(name)
    if not (case/'process.json').exists():return
    pid=runtime.load_process_id(case) if hasattr(runtime,'load_process_id') else json.loads((case/'process.json').read_text())['pid']
    if not runtime.process_alive(pid):return
    with contextlib.redirect_stdout(io.StringIO()):
        runtime.call(argparse.Namespace(name=name,file=None,json=json.dumps({'stop':True}),timeout=90))
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD]
    kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.WaitForSingleObject.argtypes=[wintypes.HANDLE,wintypes.DWORD]
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    handle=kernel.OpenProcess(0x100000|0x1000,False,pid)
    if handle:
        try:
            if kernel.WaitForSingleObject(handle,90000)==0x102:raise TimeoutError('Owned client did not stop: '+name)
        finally:kernel.CloseHandle(handle)
    print('CLOSED',name,flush=True)


def prepare(name,jar,dh=False,plain=False,jvm=None):
    runtime.prepare(argparse.Namespace(name=name,mod=str(jar),dh=dh,plain=plain))
    runtime.launch(argparse.Namespace(name=name,jvm=jvm))


def main():
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument('--prefix',required=True)
    parser.add_argument('--jar',required=True)
    parser.add_argument('--disable-meter-flag',required=True)
    parser.add_argument('--suite',choices=['all','core','plain','dh','glass','meter'],default='all')
    args=parser.parse_args()
    jar=Path(args.jar).resolve(strict=True)
    active=[]
    try:
        if args.suite in ('all','core'):
            name=args.prefix+'-core';active.append(name);prepare(name,jar)
            run(name,'matrix','run-live-matrix.py','--name',name,'--mode','windows','--tags','vanilla,itrp,complementary,photon,bsl')
            stop(name);active.remove(name)
        if args.suite in ('all','plain'):
            name=args.prefix+'-plain';active.append(name)
            run_dir=runtime.paths(name)[0];run_dir.mkdir(parents=True,exist_ok=True)
            run(name,'prepare','advanced-runtime-regression.py','prepare','--name',name,'--mod',str(jar))
            runtime.launch(argparse.Namespace(name=name,jvm=None))
            run(name,'advanced','advanced-runtime-regression.py','run','--name',name)
            stop(name);runtime.launch(argparse.Namespace(name=name,jvm=None))
            run(name,'rejoin','advanced-runtime-regression.py','rejoin','--name',name)
            stop(name);active.remove(name)
        if args.suite in ('all','dh'):
            for mode in ('off','itrp'):
                name=args.prefix+'-dh-'+mode;active.append(name);prepare(name,jar,dh=True)
                run(name,'dh-smoke','dh-runtime-smoke.py','--name',name,'--pack',mode)
                stop(name);active.remove(name)
        if args.suite in ('all','glass'):
            for dh in (False,True):
                name=args.prefix+('-glass-dh' if dh else '-glass');active.append(name);prepare(name,jar,dh=dh)
                packs=('itrp',) if dh else ('itrp','complementary','photon','bsl')
                for pack in packs:
                    extra=['--require-bridge'] if pack=='itrp' else []
                    run(name,'glass-'+pack,'glass-refraction-regression.py','--name',name,'--pack',pack,*extra)
                stop(name);active.remove(name)
        if args.suite in ('all','meter'):
            for disabled in (False,True):
                name=args.prefix+('-meter-negative' if disabled else '-meter');active.append(name)
                flags=['-D'+args.disable_meter_flag+'=true'] if disabled else None
                prepare(name,jar,jvm=flags)
                extra=[] if disabled else ['--require-stable','--require-proxy']
                run(name,'meter','dark-meter-regression.py','--name',name,*extra)
                stop(name);active.remove(name)
    finally:
        for name in active:
            try:stop(name)
            except Exception as error:print('CLEANUP',name,repr(error),flush=True)
    print('FINAL ACCEPTANCE PASS',args.prefix,args.suite,flush=True)


if __name__=='__main__':main()
