#!/usr/bin/env python3
"""Preserve complete checker output while showing only a bounded summary in the session."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
# Windows pipe defaults can be GBK. Reports/logs and the host reader use UTF-8.
for stream in (sys.stdout,sys.stderr):
    if hasattr(stream,"reconfigure"): stream.reconfigure(encoding="utf-8",errors="backslashreplace")
ROOT=Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--log",required=True,type=Path)
parser.add_argument("command",nargs=argparse.REMAINDER)
args=parser.parse_args()
command=args.command[1:] if args.command[:1]==["--"] else args.command
if not command: parser.error("Supply a tools/check-*.py or tools/verify-*.py command after --")
script=(ROOT/command[0]).resolve()
if script.parent!=(ROOT/"tools").resolve() or not script.name.startswith(("check-","verify-")) or script.suffix!=".py":
    parser.error("Only this project's checker scripts are accepted")
log=args.log.resolve()
log.relative_to(ROOT)
log.parent.mkdir(parents=True,exist_ok=True)
with log.open("xb") as output:
    result=subprocess.run([sys.executable,"-B",str(script),*command[1:]],cwd=ROOT,stdout=output,stderr=subprocess.STDOUT,
        env={**os.environ,"PYTHONIOENCODING":"utf-8","PYTHONUTF8":"1"})
lines=log.read_text(encoding="utf-8",errors="replace").splitlines()
for line in lines[:3]+(["..."] if len(lines)>13 else [])+lines[-10:]:
    print(line if len(line)<=900 else line[:900]+" ... [full details in log]")
print("ORACLE_EXIT",result.returncode,"FULL_LOG",log)
raise SystemExit(result.returncode)
