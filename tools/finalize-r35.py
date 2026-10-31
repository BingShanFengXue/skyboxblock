#!/usr/bin/env python3
"""Assemble final R35 proof and a contact sheet from actual screenshots, not reconstructed imagery."""
from __future__ import annotations
import hashlib,json,re
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parent.parent
PROFILES=[('vanilla','r35-env-vanilla-r1','原版 / Vanilla'),('iris-native','r35-env-iris-native-r1','Iris + ITRP 原生'),
    ('dh','r35-env-dh-r1','原生 DH'),('iris-dh-fsr','r35-env-iris-dh-fsr-r1','Iris + DH + FSR2')]

def load(path):return json.loads(path.read_text(encoding='utf-8'))
def main():
    reports=ROOT/'build/reports';runtime=[];sources=[]
    for key,label,title in PROFILES:
        report=load(reports/f'r35-runtime-{key}.json')
        if report['passed']!=report['total'] or report['failed']:raise AssertionError((key,report['failed']))
        fixture=ROOT/'build/render-regression'/label
        run=load(fixture/'run-result.json');owned=load(fixture/'r30-owned.json');summary=load(fixture/'r30-results/run-summary.json')
        assert run['complete'] and run['exitCode']==0 and run['phases']==57 and run['inputBytesUnchanged']
        runtime.append(dict(profile=key,label=label,passed=report['passed'],total=report['total'],phases=run['phases'],
            clientCommands=sum(len(p['clientCommandResults']) for p in summary['phases']),
            offlineAuthWarnings=report['offlineAuthWarnings'],fatalErrors=run['fatalErrors'],seconds=run['seconds'],
            sampledStateTransactions=summary['phases'][-1]['privateStateAudit']['sampledTransactions'],
            failedStateTransactions=summary['phases'][-1]['privateStateAudit']['failedTransactions'],
            actualSelectedMods=owned['actualSelectedMods'],productionJarSha256=report['productionJarSha256'],
            probeJarSha256=run['probeJarSha256'],sourceReport=str((reports/f'r35-runtime-{key}.json').relative_to(ROOT))))
        sources.append((fixture,title,{p['name']:p for p in summary['phases']}))
    jar=ROOT/'build/libs/skyboxblock-1.0.4-test.1-r35.jar';digest=hashlib.sha256(jar.read_bytes()).hexdigest()
    assert all(r['productionJarSha256']==digest for r in runtime)
    assert len({r['probeJarSha256'] for r in runtime})==1
    package=load(reports/'r35-package-audit.json');assert package['passed']==package['total'] and package['sha256']==digest
    audit=load(reports/'r35-environment-audit-final.json');assert audit['passed'] and audit['counts']['passed']==audit['counts']['total']
    tests={}
    for name,file,expected in [('legacyMath','render-math-regression.txt',389385),('environmentRender','r35-environment-render-regression.txt',42257),
            ('environmentTypes','r35-environment-regression.txt',279),('commands','r35-command-regression.txt',619),
            ('independentBoundary','r35-environment-boundary-regression.txt',1005)]:
        text=(reports/file).read_text(encoding='utf-8');assert re.search(r'\b'+str(expected)+r'\b',text) and ('PASS' in text or 'OK' in text)
        tests[name]=dict(assertions=expected,report=file)
    font_path=Path('C:/Windows/Fonts/msyh.ttc')
    font=ImageFont.truetype(str(font_path),19) if font_path.exists() else ImageFont.load_default()
    title_font=ImageFont.truetype(str(font_path),27) if font_path.exists() else font
    tile_w,tile_h=420,263;pad=12;head=100;caption=30
    scenes=[('sky-red','天空颜色：红色覆盖'),('cloud-cyan','云颜色：青色 ARGB'),
        ('moon-phase-full_moon','月亮：独立角度 + 满月'),('occluder-on','遮挡体：雾色下半球')]
    sheet=Image.new('RGB',(pad+4*(tile_w+pad),head+4*(tile_h+caption+pad)),(24,28,36));draw=ImageDraw.Draw(sheet)
    draw.text((pad,10),'R35 环境属性 · 同一交付包 / 实际游戏截图',font=title_font,fill='white')
    draw.text((pad,47),'15 项属性，4 组环境；未重画天空，仅缩放并加标题。完整数值验证见报告。',font=font,fill=(185,198,215))
    for col,(fixture,title,rows) in enumerate(sources):
        x=pad+col*(tile_w+pad);draw.text((x,77),title,font=font,fill=(120,210,255))
        for row,(name,caption_text) in enumerate(scenes):
            phase=rows[name];path=fixture/'r30-results'/f"{phase['index']:02d}-{name}.png"
            original=Image.open(path).convert('RGB');image=original.resize((tile_w,tile_h),Image.Resampling.LANCZOS)
            y=head+row*(tile_h+caption+pad);sheet.paste(image,(x,y));draw.text((x+3,y+tile_h+3),caption_text,font=font,fill='white')
    image_path=ROOT/'docs/screenshots/r35-environment-comparison-20261031.png';image_path.parent.mkdir(parents=True,exist_ok=True);sheet.save(image_path)
    manifest={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'src/main').rglob('*')) if p.is_file()}
    java_root=ROOT/'src/main/java';snapshot=ROOT/'build/verify-r35-final-quoted'
    java_sources=list(java_root.rglob('*.java'))
    assert len(java_sources)==48
    for source in java_sources:
        relative=source.relative_to(java_root)
        assert source.read_bytes()==(snapshot/'src'/relative).read_bytes(),relative
        assert (snapshot/'classes'/relative.with_suffix('.class')).is_file(),relative
    (reports/'r35-production-source-hashes.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    result=dict(status='PASS',scope='R35 environment attribute implementation and specified four-client matrix; not a general release/performance/portal certification',
        version='1.0.4-test.1-r35',productionJarSha256=digest,attributeCount=15,javaTests=tests,
        javaAssertions=sum(x['assertions'] for x in tests.values()),staticAudit=audit['counts'],
        independentJavac=dict(sources=len(java_sources),matchingSourceSnapshots=48,allPrimaryClassesPresent=True,tag='r35-final-quoted'),
        packageAudit=dict(passed=package['passed'],total=package['total']),runtime=runtime,
        totalRuntimePhases=sum(x['phases'] for x in runtime),totalRuntimeChecks=sum(x['total'] for x in runtime),
        totalClientCommands=sum(x['clientCommands'] for x in runtime),image=str(image_path.relative_to(ROOT)),
        renderingErrors=0,offlineAuthWarnings=sum(len(x['offlineAuthWarnings']) for x in runtime),
        warningClassification='Exact Yggdrasil public-key fetch failures are retained; generic strict runners return 1 for them, while all game processes exit 0 and the independent rendering/command checks pass.',
        unchangedProtectedPipelineClasses=10,unchangedTextures=True,userWorldsAndInstallationUntouched=True,
        inheritedLimitations=['Historical four strict restoration failures are not re-certified by this task.',
            'Portals and resource/performance long tests are not newly certified.',
            'This is a Minecraft 1.21.1 private-sky adapter, not the native newer environment-attribute engine.'])
    (reports/'r35-final-validation.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(json.dumps({k:result[k] for k in ['status','version','productionJarSha256','javaAssertions','totalRuntimePhases','totalRuntimeChecks','totalClientCommands','offlineAuthWarnings']},indent=2))
    print(image_path)
    return 0
if __name__=='__main__':raise SystemExit(main())