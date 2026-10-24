#!/usr/bin/env python3
"""Freeze the verified R33 meter isolation fix, original controls, and 4K-retaining resources."""
from pathlib import Path
import hashlib,json,shutil,zipfile
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]
VERSION='1.0.3-test.3-r33';PREFIX='r33-final-r1'
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    matrix=read(ROOT/f'build/reports/{PREFIX}-matrix.json');assert matrix['passed'] and matrix['complete'] and len(matrix['runs'])==13
    jar=ROOT/f'build/libs/skyboxblock-{VERSION}.jar';identity=sha(jar);assert identity==matrix['jarSha256']
    audit=read(ROOT/'build/reports/r33-package-audit.json');assert audit['jarSha256']==identity and audit['failedChecks']==0
    assert read(ROOT/'build/reports/r33-meter-math.json')['passed']
    output=ROOT.parent/'output'/f'skyboxblock-{VERSION}';archive=ROOT.parent/'artifacts/snapshots'/f'skyboxblock-{VERSION}-20261023-source-evidence.zip'
    report=ROOT/'docs/DARK-HAND-R33-DELIVERY-2026-10-23.md'
    assert not output.exists() and not archive.exists() and not report.exists(), 'Preserve existing deliveries'
    groups={};entries=[];evidence=[];reports=[ROOT/'build/reports/r33-package-audit.json',ROOT/'build/reports/r33-meter-math.json',ROOT/f'build/reports/{PREFIX}-matrix.json',ROOT/'build/reports/render-math-regression.txt']
    positive_scenes=positive_checks=0;max_white=0.;min_chroma=255.;max_sky=0.;net=[];negative=None
    for row in matrix['runs']:
        label=row['label'];directory=ROOT/'build/render-regression'/label;fixture=read(directory/'fixture.json');runtime=read(directory/'run-result.json');score=read(ROOT/f'build/reports/{label}-oracle.json')
        assert fixture['jarSha256']==identity and sha(directory/'mods'/jar.name)==identity
        assert runtime['exitCode']==0 and runtime['finished'] and not runtime['fatalErrors'] and not runtime['renderThreadViolations']
        assert score['passed'] and score['failedChecks']==0
        errors=runtime['errors'];assert all('[Yggdrasil Key Fetcher/ERROR]: Failed to request yggdrasil public key' in e for e in errors)
        if errors:net.append(dict(label=label,errors=errors))
        reports.append(ROOT/f'build/reports/{label}-oracle.json');entries += [directory/'fixture.json',directory/'run-result.json',directory/'client.log']+list((directory/'results').glob('*.json'))
        if row['negative']:negative=(directory,score);continue
        positive_scenes+=runtime['scenes'];positive_checks+=score['checks']
        env='fsr2' if row['fsr']=='2' else 'native'
        if row['static']:
            assert runtime['scenes']==22
            evidence += [directory/'results'/f'{s}.png' for s in ['material-black-foreground','material-galaxy-foreground','material-apocalypse-foreground','material-mixed-reload','material-mixed-resized']]
            continue
        assert runtime['scenes']==15
        groups[(env,row['material'])]=(directory,score)
        rows=score['measurements'];max_white=max(max_white,max(r['whiteFraction248'] for r in rows));min_chroma=min(min_chroma,min(r['meanHandChannelSpan'] for r in rows))
        max_sky=max(max_sky,max((r['skyColor']['mae'] for r in rows if r['skyColor']),default=0))
        evidence += [directory/'results'/f'{s}.png' for s in ['hand-outside-start','hand-inside-30','hand-reenter-30','hand-removed-10']]
    assert negative and len(groups)==10
    negative_dir,negative_score=negative
    neg_by={r['scene']:r for r in negative_score['measurements']}
    assert neg_by['hand-inside-30']['whiteFraction248']>.9 and neg_by['hand-inside-30']['exposureMultiplier']>100
    evidence += [negative_dir/'results'/'hand-inside-30.png',ROOT/'build/render-regression/r33-baseline-black-fsr2/results/hand-inside-30.png']
    # The no-Iris smoke is intentionally scoped to four mixed/reload/resize/removal scenes.
    smoke=ROOT/'build/render-regression/r33-vanilla-smoke';smoke_score=read(ROOT/'build/reports/r33-vanilla-smoke-oracle.json');smoke_run=read(smoke/'run-result.json');smoke_fixture=read(smoke/'fixture.json')
    assert smoke_score['passed'] and smoke_score['scenes']==4 and smoke_run['exitCode']==0 and smoke_fixture['jarSha256']==identity
    reports += [ROOT/'build/reports/r33-vanilla-smoke-oracle.json']
    entries += [smoke/'fixture.json',smoke/'run-result.json',smoke/'client.log']+list((smoke/'results').glob('*.json'))
    output.mkdir(parents=True)
    before=Image.open(negative_dir/'results/hand-inside-30.png').convert('RGB').resize((640,400),Image.Resampling.LANCZOS)
    fixed_dir,fixed_score=groups[('fsr2','black')];after=Image.open(fixed_dir/'results/hand-inside-30.png').convert('RGB').resize((640,400),Image.Resampling.LANCZOS)
    comparison=Image.new('RGB',(1280,428),'#202631');comparison.paste(before,(0,0));comparison.paste(after,(640,0));draw=ImageDraw.Draw(comparison)
    fix_by={r['scene']:r for r in fixed_score['measurements']}
    draw.text((6,407),f"same JAR, proxy OFF: exposure {neg_by['hand-inside-30']['exposureMultiplier']:.3f}",fill='white')
    draw.text((646,407),f"same JAR, proxy ON: exposure {fix_by['hand-inside-30']['exposureMultiplier']:.3f}",fill='white')
    comparison.save(output/'r33-black-room-before-after.png')
    overview=Image.new('RGB',(1600,544),'#202631');draw=ImageDraw.Draw(overview)
    for row,env in enumerate(['native','fsr2']):
        for col,material in enumerate(['black','galaxy','apocalypse','mixed']):
            d,s=groups[(env,material)];im=Image.open(d/'results/hand-inside-30.png').convert('RGB').resize((400,250),Image.Resampling.LANCZOS);overview.paste(im,(col*400,row*272));draw.text((col*400+5,row*272+252),env+' / '+material+' / 30 seconds',fill='white')
    overview.save(output/'r33-dark-room-native-fsr2.png')
    summary=dict(version=VERSION,jarSha256=identity,positiveScenes=positive_scenes,positiveChecks=positive_checks,negativeScenes=15,noIrisSmokeScenes=4,noIrisSmokeChecks=smoke_score['checks'],
        maximumHandWhiteFraction248=max_white,minimumHandChroma=min_chroma,maximumSkyMeanRGBError=max_sky,negativeInsideExposure=neg_by['hand-inside-30']['exposureMultiplier'],fixedInsideExposure=fix_by['hand-inside-30']['exposureMultiplier'],
        packageChecks=audit['checks'],mathAssertions=389385,meterNumericSamples=100000,externalNetworkErrors=net)
    summary_path=ROOT/'build/reports/r33-final-acceptance.json';summary_path.write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8');reports.append(summary_path)
    table=[]
    for material,name in [('black','纯黑'),('galaxy','银河'),('apocalypse','末日陆地'),('mixed','三者混合'),('sky','原版天空对照')]:
        a=groups[('native',material)][1];b=groups[('fsr2',material)][1]
        table.append(f"| {name} | 15 / {a['passedChecks']}/{a['checks']} | 15 / {b['passedChecks']}/{b['checks']} |")
    report.write_text(f'''# R33 光影暗色天空手臂泛白修复交付

日期：2026-10-23。版本：**{VERSION}**。

## 修复内容

修复当前Iris/ITRP光影组合下，末日陆地、银河、纯黑及三者混合密闭空间引起的手臂过曝变白。保留R32运动／后排轮廓修复、晴空A/B名称与两张4K PNG。

原因是艺术化暗色显示背景参与了物理相机测光：纯黑房内平均亮度降至0.00274，曝光增益约360.75，真实手臂内部像素全部白剪切。新实现只为原光影曝光compute提供私有测光输入代理，对实际可见的三种暗天空建立物理天空／18%线性中性参考的测光下限。更亮的真实样本不降低，普通非目标材质不启用。

原曝光compute照常运行并自行更新曝光；不关闭自动曝光或泛光、不修改shader ZIP、不画手臂补丁、不直接修改世界或泛光图像。只在这一次dispatch期间替换一个sampler，finally恢复。实际场景RGB与天空显示仍走原有路径；GPU只处理原compute已使用的低分辨率测光图集，正常游戏没有新增CPU颜色读回。

## 同包实机结果

环境：MC1.21.1/Fabric、Iris1.8.1、Sodium0.6.7、DH3.3.3、ITRP Alpha0.8.31。原生和FSR2分别验证。

| 密闭材料 | 原生：场景 / 检查 | FSR2：场景 / 检查 |
|---|---:|---:|
{chr(10).join(table)}

每组15场景包含进入、驻留30秒、离开、再次进入驻留30秒、移除恢复。固定白天6000、实际天空光15／方块光0，隔离材质造成的虚假暗处适应，不用锁定曝光。光影定量测试沿用MOTION_BLUR=false；用户配置没有改动。

加上原生／FSR各22个定向前景、混材、重载、缩放、移除回归，正向共 **{positive_scenes}场景、{positive_checks}/{positive_checks}检查**。另有无Iris四场景冒烟 **{smoke_score['passedChecks']}/{smoke_score['checks']}**，不是全量无光影验收。

- 全部正向手臂最大白剪切比例：**{max_white:.6%}**（真实手部深度掩码内部RGB各通道≥248）。最小平均通道色差：{min_chroma:.3f}，肤色纹理保留。
- 所有采样的手臂最终回填前后差异为0，证明不是在最终画面重涂手臂。
- 同JAR关闭代理的负对照15场景仍复现白手臂；纯黑30秒曝光 **{neg_by['hand-inside-30']['exposureMultiplier']:.3f} → {fix_by['hand-inside-30']['exposureMultiplier']:.3f}**。
- 目标暗天空及混合室的显示颜色按R32独立方向oracle核对，最大平均绝对RGB误差 **{max_sky:.6f}/255**；纯黑仍为纯黑。
- 原版对照全程要求代理不启用；所有目标场景确认代理实际启用且输入sampler已恢复。
- 实际包／保留范围审计 **{audit['passedChecks']}/{audit['checks']}**：所有原颜色shader、方向数学和PNG与R32-4K基线逐字节一致，两张晴空PNG4096×2048。
- 数学回归389,385断言；另有100,000个曝光／覆盖／颜色输入的有限半浮点、零覆盖不变及更亮样本保留属性检查通过。

同包负对照与修复图来自两次独立固定场景运行，不冒称同一帧。外部认证网络日志如有保留在摘要和原始日志，不归类为模组渲染故障；没有删改原始输出。

## 包身份与复现

主包SHA-256：

```text
{identity}
```

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -Task build
python tools/verify-r33-package.py
python tools/render-regression.py --profile iris-dh --suite handexposure --hand-material black --jar build/libs/skyboxblock-{VERSION}.jar --label NEW_LABEL --shader "iterationRP Alpha 0.8.31.zip" --shader-settings instance --shader-option FSR2_SCALE=2 --shader-option MOTION_BLUR=false --timeout 700
python tools/check-r33-hand.py build/render-regression/NEW_LABEL --expect fixed --output build/reports/NEW_LABEL.json
```

材料可换成galaxy、apocalypse、mixed；同JAR负对照增加--negative-dark-meter，并用--expect negative检查。所有实例实际安装的JAR与交付包相同。

## 范围

仍为有范围测试版；测光适配仅在已校验的ITRP包／pipeline／输入图像归属成立时启用，不扩为所有光影版本通用兼容。资源／性能长测未启动，旧严格恢复四红、门户及其它历史暂缓项保留；没有新增独立第三方终审。

用户安装包、存档和光影ZIP未替换；旧归档只保留不覆盖。源码包不包含提取分析的第三方shader正文／游戏JAR／用户存档。素材来源声明继续适用。

过程见 [执行记录](<DARK-HAND-R33-WORKLOG-2026-10-23.md>)。
''',encoding='utf-8')
    for path in [jar,report,ROOT/'docs/DARK-HAND-R33-WORKLOG-2026-10-23.md']+reports:shutil.copy2(path,output/path.name)
    evidence += [output/'r33-black-room-before-after.png',output/'r33-dark-room-native-fsr2.png']
    entries += [p for folder in ['src','tools','LICENSES','gradle'] for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts]
    entries += [ROOT/n for n in ['README.md','LICENSE','AUTHORS.md','THIRD_PARTY_NOTICES.md','build.gradle','gradle.properties','settings.gradle','gradlew','gradlew.bat']]
    entries += list((ROOT/'docs').glob('*R33*.md'))+reports+evidence+[jar,ROOT/'build/r33-meter-build.log',ROOT/'build/r33-baseline-build.log']
    baseline=ROOT.parent/'output/skyboxblock-1.0.3-test.2-r32-4k/skyboxblock-1.0.3-test.2-r32-4k.jar';entries.append(baseline)
    archive.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for path in sorted(set(entries)):
            if path.is_relative_to(ROOT):name='skybox-mod/'+path.relative_to(ROOT).as_posix()
            elif path==baseline:name=path.relative_to(ROOT.parent).as_posix()
            else:name='selected-evidence/'+path.name
            z.write(path,name)
    shutil.copy2(archive,output/archive.name)
    (output/'SHA256SUMS.txt').write_text('\n'.join(sha(p)+'  '+p.name for p in sorted(output.iterdir()) if p.is_file())+'\n',encoding='utf-8')
    print(json.dumps(summary,indent=2));print('OUTPUT',output);print('SNAPSHOT',archive)
if __name__=='__main__':main()
