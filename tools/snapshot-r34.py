from pathlib import Path
import hashlib,json,zipfile
root=Path(__file__).resolve().parents[1]
out=root.parent/'artifacts/snapshots/skybox-r34-baseline-r33-20261023.zip'
assert not out.exists(),out
files=[p for folder in ['src','tools','docs'] for p in (root/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts]
files += [root/n for n in ['gradle.properties','build.gradle','README.md','AUTHORS.md']]
files += [root/'build/libs/skyboxblock-1.0.3-test.3-r33.jar']
out.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(out,'x',zipfile.ZIP_DEFLATED) as z:
 for p in files:z.write(p,'skybox-mod/'+p.relative_to(root).as_posix())
print(json.dumps(dict(snapshot=str(out),files=len(files),sha256=hashlib.sha256(out.read_bytes()).hexdigest()),indent=2))
