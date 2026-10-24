"""Owned R34 edge fixture runner; no user instance modifications."""
from pathlib import Path
import argparse,subprocess,sys,json,hashlib,time
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--prefix',required=True);p.add_argument('--modes',default='fsr2,native,vanilla');p.add_argument('--scenes');p.add_argument('--negative-surface-layer',action='store_true');a=p.parse_args()
 rows=[]
 for mode in a.modes.split(','):
  label=a.prefix+'-'+mode;log=ROOT/'build'/f'{label}.log'
  cmd=[sys.executable,str(ROOT/'tools/render-regression.py'),'--jar',str(a.jar.resolve()),'--suite','edges','--profile','vanilla' if mode=='vanilla' else ('dh' if mode=='dh' else 'iris-dh'),'--label',label,'--timeout','1500']
  if mode not in ['vanilla','dh','iris-off-dh']:cmd+=['--shader','iterationRP Alpha 0.8.31.zip','--shader-settings','instance','--shader-option',f'FSR2_SCALE={2 if mode=="fsr2" else -1}','--shader-option','MOTION_BLUR=false']
  if a.scenes:cmd+=['--scenes',a.scenes]
  if a.negative_surface_layer:cmd+=['--negative-surface-layer']
  print('START',label,flush=True);start=time.monotonic()
  with log.open('wb') as f:code=subprocess.call(cmd,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT)
  rows.append(dict(label=label,exitCode=code,seconds=time.monotonic()-start,command=cmd));print('DONE',label,code,flush=True)
  out=ROOT/'build/reports'/f'{a.prefix}-runs.json';out.parent.mkdir(exist_ok=True,parents=True)
  out.write_text(json.dumps(dict(jarSha256=hashlib.sha256(a.jar.read_bytes()).hexdigest(),runs=rows),indent=2)+'\n',encoding='utf-8')
  if code:return code
 return 0
if __name__=='__main__':raise SystemExit(main())
