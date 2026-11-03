#!/usr/bin/env python3
"""Verify complete final-jar acceptance, package bytes, and create a raw-image overview."""
from pathlib import Path
from datetime import datetime
import argparse
import hashlib
import json
import re
import shutil
import zipfile
import numpy as np
from PIL import Image, ImageDraw

PROJECT=Path(__file__).resolve().parents[1]
ROOT=PROJECT/'build/render-regression'
JAR=PROJECT/'build/libs/skyboxblock-1.0.6-test.1-port.2-mc26.2.jar'


def load(path):return json.loads(path.read_text(encoding='utf-8'))


def main():
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument('--prefix',default='verified-port2')
    args=parser.parse_args();prefix=args.prefix
    digest=hashlib.sha256(JAR.read_bytes()).hexdigest()
    cases=[(prefix+'-core','matrix-report.json'),(prefix+'-plain','advanced-report.json'),
           (prefix+'-plain','rejoin-report.json'),(prefix+'-dh-off','dh-smoke-report.json'),
           (prefix+'-dh-itrp','dh-smoke-report.json'),(prefix+'-glass-dh','glass-itrp-report.json'),
           (prefix+'-meter','dark-meter-report.json')]
    cases += [(prefix+'-glass','glass-'+pack+'-report.json') for pack in ['itrp','complementary','photon','bsl']]
    negative_case=prefix+'-meter-negative'
    reports={};all_results=[]
    for name,filename in cases:
        case=ROOT/name;report=load(case/filename)
        assert report.get('passed') and not report.get('failures'),(name,report.get('failures'))
        assert load(case/'preparation.json')['modSha256']==digest,('jar identity mismatch',name)
        reports[name+'/'+filename]={'passed':True,'scenes':len(report['results'])}
        all_results.extend(report['results'])
    negative=load(ROOT/negative_case/'dark-meter-report.json')
    assert negative.get('passed') and not negative.get('failures'),('negative-control execution failed',negative.get('failures'))
    assert load(ROOT/negative_case/'preparation.json')['modSha256']==digest,'negative-control jar differs'
    assert all(not row.get('mouseGrabbed') and not row.get('windowFocused') and not row.get('windowVisible')
               for row in all_results+negative['results']),'focus/mouse regression'
    main_report=load(ROOT/(prefix+'-core')/'matrix-report.json')
    pairs=[row['sameMaterialDepthDifference'] for row in main_report['results'] if 'sameMaterialDepthDifference' in row]
    oracles=[row['panoramaOracle'] for row in main_report['results'] if 'panoramaOracle' in row]
    moving=[row for row in main_report['results'] if re.search(r'-movement-[0-5]$',row['label'])]
    advanced=load(ROOT/(prefix+'-plain')/'advanced-report.json')
    moon=next(row['moonOracle'] for row in advanced['results'] if 'moonOracle' in row)
    cloud=next(row['cloudReload'] for row in advanced['results'] if 'cloudReload' in row)
    enabled=load(ROOT/(prefix+'-meter')/'dark-meter-report.json')
    on=enabled['darkToNaturalRatios'];off=negative['darkToNaturalRatios']
    assert all(.85<value<1.15 for value in on.values()),on
    assert np.mean(list(off.values()))-np.mean(list(on.values()))>.1,('meter negative control not sensitive',on,off)
    active_log=(ROOT/(prefix+'-meter')/'client.log').read_text(encoding='utf-8',errors='replace')
    assert 'composite74 sampler proxy active' in active_log
    assert 'original atlas texture+sampler+activeUnit restored after compute' in active_log
    negative_log=(ROOT/negative_case/'client.log').read_text(encoding='utf-8',errors='replace')
    assert 'fixture negative control; original exposure compute retained' in negative_log
    critical=re.compile(r'Missing program skyboxblock:|MixinApplyError|InjectionError|FATAL ERROR in native method|'
                        r'Exception rendering|Failed to compile.*skybox|framebuffer status=|meter-validation:|'
                        r'ITRP dark meter unavailable|ITRP scene bridge opaque-validation:|ITRP scene bridge scene-validation:')
    errors={}
    for name in sorted({name for name,_ in cases}):
        text=(ROOT/name/'client.log').read_text(encoding='utf-8',errors='replace')
        found=[line for line in text.splitlines() if critical.search(line)]
        assert not found,(name,found);errors[name]=found
    with zipfile.ZipFile(JAR) as archive:
        mod=json.loads(archive.read('fabric.mod.json'))
        assert mod['depends']['minecraft']=='26.2' and mod['version'].endswith('port.2')
        for locale in ['en_us','zh_cn']:
            assert archive.read('assets/skyboxblock/lang/'+locale+'.json') == (PROJECT/'../skybox-mod/src/main/resources/assets/skyboxblock/lang'/(locale+'.json')).read_bytes()
    glass={pack:load(ROOT/(prefix+'-glass')/('glass-'+pack+'-report.json'))['skyResponse'] for pack in ['itrp','complementary','photon','bsl']}
    summary={'overallStatus':'accepted','asOf':datetime.now().astimezone().isoformat(),
             'artifact':JAR.name,'sha256':digest,'size':JAR.stat().st_size,'reports':reports,
             'acceptedScenes':len(all_results),'negativeControlScenes':len(negative['results']),
             'continuity':{'pairs':len(pairs),'pixels':sum(row['pixels'] for row in pairs),'maxPixelDifference':max(row['max'] for row in pairs)},
             'panoramaOracle':{'frames':len(oracles),'worstMean':max(row['mean'] for row in oracles),'worstP99':max(row['p99'] for row in oracles)},
             'movementFrames':len(moving),'movementBobMax':max(row['bobOriginOffset'] for row in moving),
             'moonOracle':moon,'cloudReload':cloud,'glassTransmissionResponses':glass,
             'meterEnabledRatios':on,'meterDisabledRatios':off,'criticalRenderErrors':errors,
             'allWindowsHiddenAndMouseFree':True,'baselineLanguagesByteIdentical':True}
    out=PROJECT/'output';out.mkdir(exist_ok=True)
    shutil.copy2(JAR,out/JAR.name)
    (out/(JAR.name+'.sha256.txt')).write_text(digest+'  '+JAR.name+'\n',encoding='utf-8')
    (out/'port2-validation-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    panels=[('ITRP: real sky preserved',prefix+'-core','itrp-real-sky-preserved'),
            ('ITRP: walking panorama',prefix+'-core','itrp-movement-3'),
            ('Custom panorama',prefix+'-plain','custom-gradient'),
            ('Colored cloud texture',prefix+'-plain','red-clouds'),
            ('Same-size RGB/alpha reload',prefix+'-plain','blue-alpha-clouds-after-reload'),
            ('Foreground, glass, hand and HUD',prefix+'-plain','foreground-hand-glass'),
            ('ITRP: real white refraction input',prefix+'-glass','glass-itrp-sky-white'),
            ('Dark sky: meter-only input restored',prefix+'-meter','meter-black')]
    image=Image.new('RGB',(1920,2520),(28,32,40));draw=ImageDraw.Draw(image)
    for index,(title,case,label) in enumerate(panels):
        panel=Image.open(ROOT/case/'control'/(label+'.png')).convert('RGB');assert panel.size==(960,600)
        x,y=index%2*960,index//2*630;draw.text((x+10,y+8),title,fill='white');image.paste(panel,(x,y+30))
    folder=PROJECT/'docs/screenshots/port2';folder.mkdir(parents=True,exist_ok=True)
    image.save(folder/'overview.png')
    print(json.dumps(summary,ensure_ascii=True,indent=2))


if __name__=='__main__':main()
