#!/usr/bin/env python3
from pathlib import Path
import json,os,subprocess,sys
ROOT=Path(__file__).resolve().parents[1];env=os.environ.copy();env['PYTHONIOENCODING']='utf-8';env['PYTHONUTF8']='1'
cases=[('262','owner262-before-quick','iris',2,True,ROOT/'mc26.2/build/libs/skyboxblock-1.0.7-test.1-hdr-port.1-mc26.2.jar',True)]
for v in ['1211','262']:
 jar=ROOT/('build/libs/skyboxblock-1.0.7-test.2-owner.jar' if v=='1211' else 'mc26.2/build/libs/skyboxblock-1.0.7-test.2-owner-port.1-mc26.2.jar')
 for suffix,profile,fsr in [('native','vanilla',-1),('itrp-native','iris',-1),('itrp-fsr','iris',2)]:cases.append((v,'owner'+v+'-fixed-'+suffix,profile,fsr,False,jar,False))
results=[]
for version,label,profile,fsr,quick,jar,negative in cases:
 command=[sys.executable,str(ROOT/'tools'/('custom-owner-'+version+'.py')),'--label',label,'--jar',str(jar),'--profile',profile,'--fsr',str(fsr)]+(['--quick'] if quick else [])
 with (ROOT/'build'/(label+'.log')).open('wb') as f:subprocess.run(command,cwd=ROOT,env=env,stdout=f,stderr=subprocess.STDOUT,check=True)
 case=ROOT/('build' if version=='1211' else 'mc26.2/build')/'render-regression'/label
 command=[sys.executable,str(ROOT/'tools/check-custom-owner.py'),str(case)]+(['--expect-failure'] if negative else [])
 with (ROOT/'build'/(label+'-check.log')).open('wb') as f:subprocess.run(command,cwd=ROOT,env=env,stdout=f,stderr=subprocess.STDOUT,check=True)
 report=json.loads((case/'custom-owner-check.json').read_text());results.append({'version':version,'label':label,'case':str(case),'negativeControl':negative,'passes':report['passes'],'failures':report['failures'],'ok':report.get('negativeControlDetected',report['ok'])});print(json.dumps(results[-1]),flush=True)
 (ROOT/'build/custom-owner-matrix.json').write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
