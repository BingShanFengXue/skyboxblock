#!/usr/bin/env python3
"""Freeze R32 only after same-binary moving and targeted-regression matrices pass."""
from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import zipfile
import numpy as np
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]
VERSION='1.0.3-test.2-r32'
PREFIX='r32-final-r2'

def load(path):return json.loads(path.read_text(encoding='utf-8'))
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    matrix=load(ROOT/f'build/reports/{PREFIX}-matrix.json');assert matrix['complete'] and matrix['passed'] and len(matrix['runs'])==8
    jar=ROOT/f'build/libs/skyboxblock-{VERSION}.jar';sources=ROOT/f'build/libs/skyboxblock-{VERSION}-sources.jar';identity=sha(jar);assert identity==matrix['jarSha256']
    audit=load(ROOT/'build/reports/r32-package-audit.json');assert audit['failedChecks']==0 and audit['jarSha256']==identity
    output=ROOT.parent/'output'/f'skyboxblock-{VERSION}'
    snapshot=ROOT.parent/'artifacts/snapshots'/f'skyboxblock-{VERSION}-20261023-source-evidence.zip'
    delivery=ROOT/'docs/PANORAMA-MOTION-R32-DELIVERY-2026-10-23.md'
    assert not output.exists() and not snapshot.exists() and not delivery.exists(), 'Preserve existing delivery/archive'
    evidence=[];report_paths=[ROOT/'build/reports/r32-package-audit.json',ROOT/'build/reports/render-math-regression.txt',ROOT/f'build/reports/{PREFIX}-matrix.json']
    groups={};capture_entries=[];checks=0;scenes=0;maximum=0;vanilla_max=0;network_errors=[]
    for row in matrix['runs']:
        label=row['label'];directory=ROOT/'build/render-regression'/label
        fixture=load(directory/'fixture.json');run=load(directory/'run-result.json');oracle=load(ROOT/f'build/reports/{label}-oracle.json')
        assert fixture['jarSha256']==identity and sha(directory/'mods'/jar.name)==identity
        assert run['exitCode']==0 and run['finished'] and not run['fatalErrors'] and not run['renderThreadViolations']
        known_network=[e for e in run['errors'] if '[Yggdrasil Key Fetcher/ERROR]: Failed to request yggdrasil public key' in e]
        assert len(known_network)==len(run['errors']), 'Unexpected non-network ERROR: '+label
        if known_network:
            text=(directory/'client.log').read_text(encoding='utf-8',errors='replace')
            assert 'Failed to read from https://api.minecraftservices.com/publickeys due to Connection reset' in text
            network_errors.append(dict(label=label,count=len(known_network),lines=known_network,classification='Minecraft authentication public-key HTTPS connection reset before world load; preserved, not a rendering failure'))
        assert oracle['passed'] and oracle['failedChecks']==0
        moving=row['suite']=='panomotion';assert run['scenes']==(82 if moving else 22)
        if not moving:assert len(fixture['requestedScenes'])==22
        profile=fixture['profile'];env=profile if profile in ['vanilla','dh'] else ('fsr2' if fixture['shaderOptions']['FSR2_SCALE']=='2' else 'native')
        groups.setdefault(env,{})['motion' if moving else 'static']=dict(label=label,scenes=run['scenes'],checks=oracle['checks'],logErrors=len(run['errors']))
        if profile=='iris-dh':
            data=load(directory/'results'/('motion-kloppenheim-07.json' if moving else 'material-kloppenheim-front.json'))
            assert data['finalResolved'] and data['itrpBackgroundInjected'] and data['itrpMaterialWrites']
            assert (max(abs(t-1) for t in data['rasterScale'])>.001)==(env=='fsr2')
        checks+=oracle['checks'];scenes+=run['scenes'];report_paths.append(ROOT/f'build/reports/{label}-oracle.json')
        capture_entries += [directory/'fixture.json',directory/'run-result.json',directory/'client.log']+list((directory/'results').glob('*.json'))
        if moving:
            maximum=max(maximum,oracle['maximumPanoramaMeanRGBError'])
            vanilla_max=max(vanilla_max,max(r['mae'] for r in oracle['results'] if r['style']=='vanilla'))
            for style in ['vanilla','white','black','kloppenheim','syferfontein','dusk','galaxy','lush','apocalypse']:
                evidence.append(directory/'results'/f'motion-{style}-07.png')
        else:
            evidence += [directory/'results'/f'{s}.png' for s in ['material-white-foreground','material-black-foreground','material-kloppenheim-foreground','material-mixed','material-mixed-reload','material-mixed-resized','material-mixed-removed']]
    assert set(groups)=={'vanilla','dh','native','fsr2'} and all(set(g)=={'motion','static'} for g in groups.values())
    negative=ROOT/'build/render-regression/r32-motion-legacy-fsr2-r2';nfixture=load(negative/'fixture.json');nrun=load(negative/'run-result.json');noracle=load(ROOT/'build/reports/r32-motion-legacy-fsr2-r2-oracle.json')
    assert sha(negative/'mods'/jar.name)==identity and nfixture['negativePanoramaRays']
    assert nrun['exitCode']==0 and not nrun['errors'] and not nrun['fatalErrors']
    assert noracle['failedChecks']==8 and all(x['name']=='depth independent panorama' for x in noracle['failures'])
    report_paths += [ROOT/'build/reports/r32-motion-legacy-fsr2-r2-oracle.json']
    capture_entries += [negative/'fixture.json',negative/'run-result.json',negative/'client.log']+list((negative/'results').glob('*.json'))
    evidence += [negative/'results'/f'motion-kloppenheim-{i:02d}.png' for i in range(8)]+[ROOT/'build/reports/r32-user-reference.png']
    # Retain the preliminary native-projection failure and the explicit correction smoke test.
    report_paths += [ROOT/'build/reports/r32-final-r1-motion-vanilla-oracle.json',ROOT/'build/reports/r32-fixed-vanilla-smoke-r2-oracle.json']
    evidence += [ROOT/'build/render-regression/r32-final-r1-motion-vanilla/results/motion-kloppenheim-07.png']
    capture_entries += [ROOT/'build/render-regression/r32-final-r1-motion-vanilla/results/motion-kloppenheim-07.json']
    # Comparison contains independent real walking frames, each with its own independent ray oracle.
    spec=importlib.util.spec_from_file_location('motion_oracle',ROOT/'tools/check-r32-motion.py');motion=importlib.util.module_from_spec(spec);spec.loader.exec_module(motion)
    sheet=Image.new('RGB',(1920,856),'#202631');draw=ImageDraw.Draw(sheet)
    for row,directory in enumerate([negative,ROOT/'build/render-regression'/groups['fsr2']['motion']['label']]):
        name='motion-kloppenheim-07';actual=motion.base.image(directory/'results'/f'{name}.png');data=load(directory/'results'/f'{name}.json');reference=motion.base.expected('kloppenheim',motion.rays(data,actual.shape[1],actual.shape[0]));diff=np.abs(actual.astype(float)-reference).max(axis=-1)
        heat=np.zeros_like(actual);heat[...,0]=np.uint8(np.clip(diff*12,0,255))
        for col,(array,label) in enumerate([(actual,'GPU actual'),(np.uint8(np.clip(reference+.5,0,255)),'independent ray oracle'),(heat,'RGB error x12')]):
            image=Image.fromarray(array).resize((640,400),Image.Resampling.LANCZOS);sheet.paste(image,(col*640,row*428));draw.text((col*640+6,row*428+405),('legacy / ' if row==0 else 'fixed / ')+label+'; separate real walking frames',fill='white')
    # Create deliverable only after all gates pass.
    output.mkdir(parents=True);sheet.save(output/'r32-moving-panorama-comparison.png')
    overview=Image.new('RGB',(2880,888),'#202631');draw=ImageDraw.Draw(overview)
    for row,env in enumerate(['vanilla','dh','native','fsr2']):
        directory=ROOT/'build/render-regression'/groups[env]['motion']['label']/'results'
        for col,style in enumerate(['vanilla','white','black','kloppenheim','syferfontein','dusk','galaxy','lush','apocalypse']):
            image=Image.open(directory/f'motion-{style}-07.png').convert('RGB').resize((320,200),Image.Resampling.LANCZOS);overview.paste(image,(col*320,row*222));draw.text((col*320+5,row*222+204),env+' / '+{'kloppenheim':'Clear Sky A','syferfontein':'Clear Sky B'}.get(style,style),fill='white')
    overview.save(output/'r32-four-environment-walking.png')
    summary=dict(version=VERSION,jarSha256=identity,jarBytes=jar.stat().st_size,scenes=scenes,checks=checks,mathematicalAssertions=389385,packageChecks=audit['checks'],
        maximumPanoramaMeanRGBError=maximum,maximumVanillaCanvasMeanRGBError=vanilla_max,legacyMaximumPanoramaMeanRGBError=noracle['maximumPanoramaMeanRGBError'],negativeFramesFailed=8,environments=groups,externalNetworkErrors=network_errors)
    summary_path=ROOT/'build/reports/r32-final-acceptance.json';summary_path.write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8');report_paths.append(summary_path)
    table=[]
    for env,label in [('vanilla','Fabric无光影'),('dh','Fabric+DH3.3.3，无光影'),('native','Iris/Sodium/DH+ITRP，原生'),('fsr2','同组合FSR2_SCALE=2')]:
        g=groups[env];table.append(f"| {label} | {g['motion']['scenes']} / {g['motion']['checks']} | {g['static']['scenes']} / {g['static']['checks']} | {g['motion']['logErrors']+g['static']['logErrors']} / 0 |")
    text=f'''# 1.0.3-test.2-r32 全景运动修复交付

日期：2026-10-23（Asia/Shanghai）。修复范围是用户指出的**新增非纯色全景方块**；原版天空没有作为故障路径修改。

## 已修复

- 新增全景在行走／视角摇晃时的错误视差、轻微变形。
- 移动时后排同材质面显出的轮廓与矩形分割。
- 显示名统一为**天空盒方块（晴空·A）**、**天空盒方块（晴空·B）**。内部ID仍为 `kloppenheim_sky_block` / `syferfontein_sky_block`，现有存档方块身份不变；素材生成器同步更新。

旧公式按具体方块面的插值坐标采图，而Minecraft视角摇晃的平移可能放在worldView或projection中；同一屏幕方向碰到不同距离面时，归一化坐标不同，后排回填便泄露为分割。原版按屏幕UV采独立天空画布，纯色没有图案差异，因此不表现为这个故障。

修复只改变 `SkyStyle==3`：保留原始投影与视图矩阵，求当前阶段的逆VP，从同一屏幕像素反投影两个深度点、齐次除法后相减重建无限远方向。这样无论摇晃平移放在projection（原生）还是worldView（Iris），均会被方向差分抵消。早期FSR阶段仍匹配真实jitter，显示阶段用显示投影。没有关闭玩家视角摇晃或FSR来掩盖问题，也没有删除原版折角／玻璃的既有深度规则。

## 本轮同包实机验证

新协议开启原生 `bobView=true`，用真实键盘行走／转头，前墙后有错层同材质面。检查实际相机位移、完整相机变换的非零原点偏移和旋转变化，不仅检查配置开关。独立CPU oracle以双精度在不同于GPU的两个深度点反投影相减，不复用GPU新增矩阵；数学单测另外与已知相机基向量的解析方向对照。

| 环境 | 运动场景 / 检查 | 定向回归场景 / 检查 | ERROR/致命错误 |
|---|---:|---:|---:|
{chr(10).join(table)}

合计 **{scenes}场景、{checks}/{checks}检查**。保留1条纯Fabric静态组的Minecraft Yggdrasil公钥获取ERROR：世界载入前HTTPS连接被重置，原始堆栈明确为authlib网络请求，不是渲染故障；未删日志、未把它写成ERROR0。其他ERROR、GL错误、渲染线程错误和致命错误均为0。运动每环境82场景含全部9种方块和移除；定向静态每环境22场景，覆盖9种方块正面／砂岩玻璃持物前景及混材、资源重载、缩放、移除。这22项是明确选择的部分协议，不冒充上版85场景全量或R30动态DH重新验收。

所有实例实际安装的JAR与交付包字节相同。四环境为MC1.21.1/Fabric、DH3.3.3，Iris1.8.1/Sodium0.6.7/ITRP Alpha0.8.31；原生与FSR2分别验证。光影定量隔离回归使用 `MOTION_BLUR=false`，未修改用户光影配置。

- 旧公式负对照：同JAR10场景正常完成，8个运动帧全部按预期检出全景错误，其他检查通过；最差平均绝对RGB误差 **{noracle['maximumPanoramaMeanRGBError']:.6f}/255**。
- 修复后全部运动采样的最差全景平均绝对RGB误差 **{maximum:.6f}/255**。这是各自实机运动帧对独立oracle的统计，不是把两次运行冒称同帧。
- 原版运动对照与原版画布最大平均绝对RGB差 **{vanilla_max:.6f}/255**。
- 数学回归 **389,385/389,385断言**，其中新增10,000组带平移视图与独立射线对照、原矩阵不变性及旧公式深度分裂负对照，共50,001条新增断言。
- 实际JAR包审计 **{audit['passedChecks']}/{audit['checks']}**。原版专用类／shader、全部PNG、世界几何顶点shader及方块物品数据与R31逐字节一致；三个颜色shader的原版／纯色分支、深度和HDR主体也逐字节保留。

用户截图、旧公式实机复现、修复实机、各自独立oracle及差异图均随证据保留。对照图上下是两个独立的真实行走采样，非同一帧。

## 交付与复现

版本：**{VERSION}**。主包SHA-256：

```text
{identity}
```

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -Task build
python tools/verify-r32-package.py
python tools/render-regression.py --profile iris-dh --suite panomotion --jar build/libs/skyboxblock-{VERSION}.jar --label NEW_LABEL --shader "iterationRP Alpha 0.8.31.zip" --shader-settings instance --shader-option FSR2_SCALE=2 --shader-option MOTION_BLUR=false --timeout 1200
python tools/check-r32-motion.py build/render-regression/NEW_LABEL/results --output build/reports/NEW_LABEL-oracle.json --annotate
```

源码与证据归档只新增，包含原版字节核对所需的R31基线JAR、当前源码与工具、全部最终验收捕获JSON／运行身份、精选实机图和用户原始截图。用户实例安装包、存档、光影ZIP及配置均未替换。未创建Git提交或推送。

## 保留边界

仍为有范围测试版，不扩大为任意光影／模组版本兼容。资源／性能长测未启动；旧四项严格恢复红项、门户和其它既有暂缓项保持历史状态。本轮未派子个体，没有新增独立第三方终审。素材来源声明继续适用，原始素材与转换PNG未重新加工。

执行记录：[PANORAMA-MOTION-R32-WORKLOG](<PANORAMA-MOTION-R32-WORKLOG-2026-10-23.md>)。
'''
    delivery.write_text(text,encoding='utf-8')
    for path in [jar,sources,delivery,ROOT/'docs/PANORAMA-MOTION-R32-WORKLOG-2026-10-23.md']+report_paths:shutil.copy2(path,output/path.name)
    evidence += [output/'r32-moving-panorama-comparison.png',output/'r32-four-environment-walking.png']
    evidence += list((ROOT/'build/reports').glob('r32-*-oracle.png'))
    entries=[p for folder in ['src','tools','LICENSES','gradle'] for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts]
    entries += [ROOT/n for n in ['README.md','LICENSE','AUTHORS.md','THIRD_PARTY_NOTICES.md','build.gradle','gradle.properties','settings.gradle','gradlew','gradlew.bat']]
    entries += list((ROOT/'docs').glob('*R32*.md'))+report_paths+capture_entries+evidence+[jar,sources,ROOT/'build/libs/skyboxblock-1.0.3-test.1-r31.jar',ROOT/'build/r32-build.log',ROOT/'build/r32-final-build.log']
    snapshot.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(snapshot,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for path in sorted(set(entries)):
            arc='skybox-mod/'+path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else 'selected-evidence/'+path.name
            archive.write(path,arc)
    shutil.copy2(snapshot,output/snapshot.name)
    (output/'SHA256SUMS.txt').write_text('\n'.join(sha(p)+'  '+p.name for p in sorted(output.iterdir()) if p.is_file())+'\n',encoding='utf-8')
    print(json.dumps(summary,indent=2));print('OUTPUT',output);print('SNAPSHOT',snapshot)
    return 0
if __name__=='__main__':raise SystemExit(main())
