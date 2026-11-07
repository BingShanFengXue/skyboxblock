#!/usr/bin/env python3
"""Sequential GPU matrix on final packages; background through host job runner."""
from pathlib import Path
import os, subprocess, sys
ROOT=Path(__file__).resolve().parents[1]
env=os.environ.copy();env['PYTHONIOENCODING']='utf-8';env['PYTHONUTF8']='1'
cases=[('1211','hdr-final-1211-r2',[]),('262','hdr-final-262-r2',[]),('1211','hdr-final-1211-itrp-r2',['--profile','iris','--shader','iterationRP Alpha 0.8.31.zip','--fsr','2']),('262','hdr-final-262-itrp-r2',['--shader','iterationRP Alpha 0.8.31.zip','--fsr','2'])]
for version,label,extra in cases:
 command=[sys.executable,str(ROOT/'tools'/('hdr-live-'+version+'.py')),'--label' if version=='1211' else '--name',label,*extra]
 log=ROOT/'build'/(label+'.log')
 with log.open('wb') as f:result=subprocess.run(command,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT,env=env)
 if result.returncode:raise RuntimeError(f'{label} exit {result.returncode}; see {log}')
 case=ROOT/('build' if version=='1211' else 'mc26.2/build')/'render-regression'/label
 with (ROOT/'build'/(label+'-checker.log')).open('wb') as f:
  subprocess.run([sys.executable,str(ROOT/'tools/check-hdr-live.py'),str(case),'--version',version],cwd=ROOT,stdout=f,stderr=subprocess.STDOUT,env=env,check=True)
 print(label+': LIVE + PIXEL + FOLDER PASS',flush=True)
