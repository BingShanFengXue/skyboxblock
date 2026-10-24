"""Current-JAR causal DH LOD occlusion regression; no user world or config writes."""
from pathlib import Path
import sys,importlib.util
ROOT=Path(__file__).resolve().parents[1]
s=importlib.util.spec_from_file_location('lod',ROOT/'tools/run-r30-lod-occlusion.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
if __name__=='__main__':raise SystemExit(m.main())
