#!/usr/bin/env python3
"""Visualize retained candidate-only protocol evidence; never changes source frames."""
from __future__ import annotations
from datetime import datetime
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent.parent


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    old = ROOT / 'build/render-regression/r28-full-vanilla-r4/results'
    new = ROOT / 'build/render-regression/r29-settled-vanilla-candidate-r1/results'
    output = ROOT / 'docs/screenshots/r29-water-reference-protocol-20261022.png'
    receipt = ROOT / 'build/reports/r29-candidate-visual-evidence-r1.json'
    if output.exists() or receipt.exists():
        raise FileExistsError('Keep existing visual evidence; select a new revision.')
    diagnostic_path=ROOT/'build/reports/r29-settled-vanilla-early-water-r1.json'
    diagnostic=json.loads(diagnostic_path.read_text(encoding='utf-8'))
    assert len(diagnostic['checks'])==4 and all(x['passed'] for x in diagnostic['checks'])
    assert all(all(pair['raw'][key]==0 for key in ('mae','p99','maximum'))
               for pair in diagnostic['rawRemovalDiagnostic']['pairs'].values())
    font_path = Path('C:/Windows/Fonts/msyh.ttc')
    font = ImageFont.truetype(str(font_path), 20)
    small = ImageFont.truetype(str(font_path), 16)
    title = ImageFont.truetype(str(font_path), 27)
    width, panel_height, cap_height = 640, 400, 60
    header_height, footer_height = 85, 88
    canvas = Image.new('RGB', (2*width, header_height+2*(panel_height+cap_height)+footer_height), '#151C2B')
    draw = ImageDraw.Draw(canvas)
    draw.text((20,10),'R29 原版水层：真实参考与天空遮挡',font=title,fill='white')
    draw.text((20,49),'同一 test.8；R28 历史时序 vs R29 稳定＋固定水帧协议。不是唯一变量因果对照。',font=small,fill='#C5D4EB')
    panels = [
        (old,'trans-open-water-none-marker','R28 历史：无天空方块，真实标记可见','旧数据参照不足的原始结论保持不变'),
        (new,'trans-open-water-none-marker','R29 候选：无天空方块，真实标记可见','固定水资源首帧，充分稳定后采集'),
        (old,'trans-sky-water-none-marker','R28 历史：天空方块挡住后方标记','仅供视觉索引，不用缩略图作像素判定'),
        (new,'trans-sky-water-none-marker','R29 候选：天空方块挡住后方标记','原检查器阈值不变，等候独立 neverSky 对照')]
    inputs = []
    for index,(directory,name,label,note) in enumerate(panels):
        path = directory / (name+'.png')
        metadata_path = directory / (name+'.json')
        metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
        with Image.open(path) as source:
            original_size = list(source.size)
            image = source.convert('RGB').resize((width,panel_height),Image.Resampling.LANCZOS)
        x,y = (index%2)*width, header_height+(index//2)*(panel_height+cap_height)
        canvas.paste(image,(x,y+cap_height))
        draw.text((x+13,y+7),label,font=font,fill='white')
        draw.text((x+13,y+35),note,font=small,fill='#C5D4EB')
        inputs.append(dict(image=str(path.relative_to(ROOT)),imageSha256=digest(path),metadata=str(metadata_path.relative_to(ROOT)),metadataSha256=digest(metadata_path),originalSize=original_size,scene=metadata['scene'],camera=metadata['camera'],settlement=metadata.get('fixtureSettlement')))
    y = canvas.height-footer_height
    draw.text((20,y+12),'先行诊断：4/4 水层参照有效；候选三种移除恢复 MAE / p99 / 最大通道差均为 0。',font=font,fill='#9CE9B9')
    draw.text((20,y+47),'这是单候选专项结果，不是三环境验收通过；原始 PNG 与数值报告保留，缩略图不参与检查。',font=small,fill='#C5D4EB')
    output.parent.mkdir(parents=True,exist_ok=True)
    with output.open('xb') as stream:
        canvas.save(stream,format='PNG')
    report = dict(kind='R29-candidate-only-visual-index-not-final-acceptance',observedAt=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),output=str(output.relative_to(ROOT)),outputSha256=digest(output),inputs=inputs,sourceImagesModified=False,resampling='Lanczos thumbnail only; original PNGs are the actual oracle inputs',causalSingleVariableControl=False,fullMatrixCompleted=False,releaseAccepted=False)
    with receipt.open('x',encoding='utf-8') as stream:
        json.dump(report,stream,ensure_ascii=False,indent=2,allow_nan=False)
        stream.write('\n')
    print(json.dumps(dict(output=str(output),receipt=str(receipt),sourceFrames=len(inputs)),ensure_ascii=False))


if __name__ == '__main__':
    main()
