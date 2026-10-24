#!/usr/bin/env python3
"""Freeze verified R31 binary, source and selected real evidence; archive is append-only."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile
from PIL import Image,ImageDraw

ROOT=Path(__file__).resolve().parents[1]
VERSION='1.0.3-test.1-r31'
PREFIX='r31-final-r3'
STYLES=['vanilla','white','black','kloppenheim','syferfontein','dusk','galaxy','lush','apocalypse']
ENVS=['vanilla','dh','native','fsr2']

def read(path):return json.loads(path.read_text(encoding='utf-8'))
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    matrix=read(ROOT/f'build/reports/{PREFIX}-matrix.json')
    assert matrix.get('complete') and matrix.get('passed'), 'Matrix not complete/passed'
    assert len(matrix['runs'])==5 and matrix['runs'][-1]['expectedFailureObserved']
    jar=ROOT/f'build/libs/skyboxblock-{VERSION}.jar';sources=ROOT/f'build/libs/skyboxblock-{VERSION}-sources.jar'
    identity=sha(jar);assert identity==matrix['jarSha256']
    audit=read(ROOT/'build/reports/r31-package-audit.json');assert audit['failed']==0 and audit['sha256']==identity
    output=ROOT.parent/'output'/f'skyboxblock-{VERSION}'
    assert not output.exists(), f'Delivery directory already exists; preserve it: {output}'
    checks=[]; selected=[];total=0
    def need(name,condition,**detail):
        checks.append(dict(name=name,passed=bool(condition),**detail))
        assert condition,(name,detail)
    for env in ENVS:
        directory=ROOT/f'build/render-regression/{PREFIX}-{env}';fixture=read(directory/'fixture.json');result=read(directory/'run-result.json')
        oracle=read(ROOT/f'build/reports/{PREFIX}-{env}-oracle.json')
        need(env+' source hash',fixture['jarSha256']==identity)
        need(env+' actual installed JAR',sha(directory/'mods'/jar.name)==identity)
        need(env+' complete scenes',result['scenes']==85 and result['finished'] and result['exitCode']==0)
        need(env+' no errors',not result['errors'] and not result['fatalErrors'] and not result['renderThreadViolations'])
        need(env+' complete independent oracle',oracle['passed'] and oracle['scenes']==85 and oracle['failedChecks']==0)
        total+=oracle['checks']
        if env in ['native','fsr2']:
            need(env+' exact shader FSR option',fixture['shaderOptions']['FSR2_SCALE']==('-1' if env=='native' else '2'))
            captures=[read(directory/'results'/f'material-{style}-front.json') for style in STYLES]
            need(env+' actual Iris HDR bridge',all(x['itrpMaterialWrites'] and x['itrpBackgroundInjected'] and x['finalResolved'] for x in captures))
            need(env+' actual raster mode',all((max(abs(t-1) for t in x['rasterScale'])<1e-5)==(env=='native') for x in captures),scales=[x['rasterScale'] for x in captures])
        for style in STYLES:selected.append(directory/'results'/f'material-{style}-front.png')
        selected += [directory/'results'/f'material-{style}-{view}.png' for style,view in [('white','foreground'),('black','foreground'),('apocalypse','joint'),('galaxy','wrap')]]
        selected += [directory/'results'/f'{name}.png' for name in ['material-mixed','material-mixed-reload','material-mixed-resized','material-mixed-removed']]
    negative=ROOT/f'build/render-regression/{PREFIX}-negative-ownership'
    negfixture=read(negative/'fixture.json');negresult=read(negative/'run-result.json');negoracle=read(ROOT/f'build/reports/{PREFIX}-negative-ownership-oracle.json')
    need('negative actual installed same JAR',sha(negative/'mods'/jar.name)==identity and negfixture['negativeMaterialOwnership'])
    need('negative finishes normally',negresult['exitCode']==0 and negresult['finished'] and not negresult['fatalErrors'])
    need('negative independent pixels fail',not negoracle['passed'] and negoracle['failedChecks']>0)
    need('negative fails only expected material colors',all(x['name'].startswith('mixed ') and x['name'].endswith('ray oracle') for x in negoracle['failures']))
    selected += [negative/'results'/'material-mixed.png']
    selected += [ROOT/'build/render-regression/r31-final-r2-dh/results'/f'material-black-foreground{suffix}.png' for suffix in ['', '-before', '-opaque']]
    selected += [ROOT/'build/render-regression/r31-native-depth-fix-r3/results/material-black-foreground.png']
    # Only create output after all identity/acceptance checks succeed.
    output.mkdir(parents=True)
    for path in [jar,sources]:shutil.copy2(path,output/path.name)
    for name in ['MATERIALS-R31-DELIVERY-2026-10-23.md','MATERIALS-R31-WORKLOG-2026-10-23.md']:
        shutil.copy2(ROOT/'docs'/name,output/name)
    sheet=Image.new('RGB',(9*320,4*222),'#202631');draw=ImageDraw.Draw(sheet)
    for row,env in enumerate(ENVS):
        for col,style in enumerate(STYLES):
            path=ROOT/f'build/render-regression/{PREFIX}-{env}/results/material-{style}-front.png'
            im=Image.open(path).convert('RGB').resize((320,200),Image.Resampling.LANCZOS)
            sheet.paste(im,(col*320,row*222));draw.text((col*320+5,row*222+204),env+' / '+style,fill='white')
    sheet.save(output/'r31-four-environment-materials.png')
    compare=Image.new('RGB',(1280,844),'#202631');draw=ImageDraw.Draw(compare)
    for i,(env,label) in enumerate([('native','native: fixed'),('fsr2','FSR2: fixed'),('vanilla','Fabric: fixed'),('negative-ownership','FSR2: ownership disabled (negative)')]):
        path=ROOT/f'build/render-regression/{PREFIX}-{env}/results/material-mixed.png'
        im=Image.open(path).convert('RGB').resize((640,400),Image.Resampling.LANCZOS)
        compare.paste(im,((i%2)*640,(i//2)*422));draw.text(((i%2)*640+5,(i//2)*422+404),label,fill='white')
    compare.save(output/'r31-mixed-occlusion-control.png')
    selected += [output/'r31-four-environment-materials.png',output/'r31-mixed-occlusion-control.png',ROOT/'build/reports/r31-material-overview.png']
    result=dict(version=VERSION,jarSha256=identity,jarBytes=jar.stat().st_size,sourceJarSha256=sha(sources),renderScenes=340,
        renderChecks=total,packageChecks=audit['checks'],mathAssertions=339384,negativeScenes=4,negativeFailedChecks=negoracle['failedChecks'],
        verificationChecks=len(checks),checks=checks,selectedEvidenceFiles=len(selected),limitations=['No resource/performance long test','No renewed universal DH/portal compatibility claim','Historical four strict restoration failures remain historical open items','User supplied third-party material rights not independently verified'])
    (ROOT/'build/reports/r31-final-acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (output/'r31-final-acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    report_paths=[ROOT/'build/reports/r31-material-assets.json',ROOT/'build/reports/r31-package-audit.json',ROOT/'build/reports/r31-final-acceptance.json',ROOT/'build/reports/render-math-regression.txt']
    report_paths+=list((ROOT/'build/reports').glob(PREFIX+'*.json'))
    report_paths += [ROOT/'build/reports/r31-final-r2-dh-oracle.json',ROOT/'build/reports/r31-native-depth-fix-r3-oracle.json']
    for path in report_paths:shutil.copy2(path,output/path.name)
    # Archive contains no external game jars, worlds, configs or source originals.
    snapshot=ROOT.parent/'artifacts/snapshots'/f'skyboxblock-{VERSION}-20261023-source-evidence.zip'
    assert not snapshot.exists(),f'Append-only archive already exists: {snapshot}'
    snapshot.parent.mkdir(parents=True,exist_ok=True)
    entries=[]
    entries += [p for folder in ['src','tools','LICENSES'] for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts]
    entries += [ROOT/name for name in ['README.md','LICENSE','AUTHORS.md','THIRD_PARTY_NOTICES.md','build.gradle','gradle.properties','settings.gradle','gradlew','gradlew.bat']]
    entries += [p for p in (ROOT/'gradle').rglob('*') if p.is_file()]
    entries += list((ROOT/'docs').glob('*R31*.md'))
    entries += report_paths+[ROOT/'build/r31-final-build.log',jar,sources]
    for env in ENVS+['negative-ownership']:
        directory=ROOT/f'build/render-regression/{PREFIX}-{env}'
        entries += [directory/'fixture.json',directory/'run-result.json',directory/'client.log']
        entries += list((directory/'results').glob('material-*.json'))
    entries += selected
    with zipfile.ZipFile(snapshot,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for path in sorted(set(entries)):
            arc=('skybox-mod/'+path.relative_to(ROOT).as_posix()) if path.is_relative_to(ROOT) else 'selected-evidence/'+path.name
            archive.write(path,arc)
    shutil.copy2(snapshot,output/snapshot.name)
    allfiles=[p for p in output.iterdir() if p.is_file()]
    manifest='\n'.join(sha(p)+'  '+p.name for p in sorted(allfiles))+'\n'
    (output/'SHA256SUMS.txt').write_text(manifest,encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='checks'},indent=2))
    print('OUTPUT',output);print('SNAPSHOT',snapshot)
    return 0
if __name__=='__main__':raise SystemExit(main())
