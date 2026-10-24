#!/usr/bin/env python3
"""Run the verified Windows PowerShell/JDK build and preserve raw plus UTF-8 logs."""
import argparse
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--task",default="build")
parser.add_argument("--log",type=Path,required=True)
args=parser.parse_args()
log=args.log.resolve()
raw=log.with_suffix(log.suffix+".raw")
log.parent.mkdir(parents=True,exist_ok=True)
if log.exists() or raw.exists():
    raise SystemExit("Use a new log path; prior build evidence is append-only")
with raw.open("xb") as output:
    result=subprocess.run(["powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",
        str(ROOT/"tools/build.ps1"),"-Task",args.task],cwd=ROOT,stdout=output,stderr=subprocess.STDOUT)
data=raw.read_bytes()
if data.startswith((b"\xff\xfe",b"\xfe\xff")):
    decoded=data.decode("utf-16")
else:
    try: decoded=data.decode("utf-8-sig")
    except UnicodeDecodeError: decoded=data.decode("gb18030")
log.write_text(decoded,encoding="utf-8")
print("\n".join(decoded.splitlines()[-65:]),flush=True)
print("BUILD_EXIT",result.returncode,"LOG",log,flush=True)
raise SystemExit(result.returncode)
