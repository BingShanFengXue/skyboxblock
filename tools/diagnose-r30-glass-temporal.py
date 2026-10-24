#!/usr/bin/env python3
"""Measure fixed-geometry glass edge motion from raw captured frames; not a performance test."""
from __future__ import annotations
import argparse,importlib.util,json
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('r30_projection',ROOT/'tools/check-r30-lod-occlusion.py')
geom=importlib.util.module_from_spec(spec);spec.loader.exec_module(geom)

def evaluate(directory):
    root=directory/'r30-results'; summary=json.loads((root/'run-summary.json').read_text(encoding='utf-8'))
    phases=summary['phases']; out=[]; samples={}
    for phase in phases:
        stem=f"{phase['index']:02d}-{phase['name']}"; files=sorted((root/'temporal').glob(stem+'-frame-*.json'))
        metadata=[json.loads(p.read_text(encoding='utf-8')) for p in files]
        if not metadata:raise ValueError('No actual temporal frames for '+stem)
        camera=np.array([m['camera'] for m in metadata]); w,h=metadata[0]['width'],metadata[0]['height']
        if np.max(np.abs(camera-camera[0]))>1e-5:raise ValueError('Camera moved during temporal control')
        # Glass is the actual fixed x=-4..4, y=136..148, z=-1 wall. Mid-cell
        # scan rows avoid horizontal borders and all private cloud geometry below the horizon.
        centers=[]
        for row in (140,141):
            pp=geom.project([(0,row,-1),(0,row+1,-1)],phase,w,h)
            centers.append(int(round(pp[:,1].mean())))
        edges=[]
        for x in (-3,-2,-1,0,1,2,3,4):
            px=float(geom.project([(x,141,-1)],phase,w,h)[0,0])
            for cy in centers:edges.append(dict(worldX=x,x=int(round(px)),y=cy,insideSky=x in (-1,0,1,2)))
        crops=[[] for _ in edges]; jitter=np.array([m['rasterJitterX'] for m in metadata])
        for m in metadata:
            with Image.open(root/'temporal'/m['image']) as image:
                for i,e in enumerate(edges):
                    crops[i].append(np.asarray(image.crop((e['x']-8,e['y']-8,e['x']+9,e['y']+9)).convert('RGB'),dtype=float))
        metrics=[]
        for e,frames in zip(edges,crops):
            arr=np.stack(frames); profile=arr.mean(axis=1); gradient=np.abs(np.diff(profile,axis=1)).mean(axis=2)
            weights=gradient.sum(axis=1); centroids=(gradient*np.arange(16)[None,:]).sum(axis=1)/np.maximum(weights,1e-9)
            energetic=float(np.median(weights))>3
            corr=float(np.corrcoef(centroids,jitter)[0,1]) if energetic and np.std(centroids)>1e-8 and np.std(jitter)>1e-12 else None
            metrics.append(dict(**e,medianGradientEnergy=float(np.median(weights)),admissibleEdge=energetic,
                centroidStdPixels=float(np.std(centroids)) if energetic else None,
                centroidP99MinusP1Pixels=float(np.percentile(centroids,99)-np.percentile(centroids,1)) if energetic else None,
                centroidJitterCorrelation=corr,pixelTemporalStd=float(arr.std(axis=0).mean()),
                p95PixelTemporalStd=float(np.percentile(arr.std(axis=0),95))))
        groups={}
        for label,flag in [('inside',True),('outside',False)]:
            selected=[e for e in metrics if e['insideSky']==flag and e['admissibleEdge']]
            groups[label]=dict(edges=len(selected),medianCentroidStd=float(np.median([e['centroidStdPixels'] for e in selected])) if selected else None,
                medianTemporalStd=float(np.median([e['pixelTemporalStd'] for e in selected])) if selected else None,
                medianAbsJitterCorrelation=float(np.median([abs(e['centroidJitterCorrelation']) for e in selected if e['centroidJitterCorrelation'] is not None])) if any(e['centroidJitterCorrelation'] is not None for e in selected) else None)
        out.append(dict(name=phase['name'],frames=len(files),uniqueJitters=len({(m['jitterBitsX'],m['jitterBitsY']) for m in metadata}),
                        cameraMaximumDeviation=float(np.max(np.abs(camera-camera[0]))),groups=groups,edges=metrics))
        if phase['name']=='sky-glass':samples=dict(phase=phase,metadata=metadata,edge=next(e for e in edges if e['worldX']==0 and e['y']>440))
    if samples:
        edge=samples['edge']; frames=samples['metadata']; order=np.argsort([m['rasterJitterX'] for m in frames]); chosen=list(order[::max(1,len(order)//12)][:12])
        canvas=Image.new('RGB',(6*170,2*165),'#202020'); draw=ImageDraw.Draw(canvas)
        for cell,index in enumerate(chosen):
            meta=frames[int(index)]; image=Image.open(root/'temporal'/meta['image']).convert('RGB')
            crop=image.crop((edge['x']-16,edge['y']-12,edge['x']+17,edge['y']+13)).resize((165,125),Image.Resampling.NEAREST)
            x=(cell%6)*170;y=(cell//6)*165;canvas.paste(crop,(x,y+35));draw.text((x+2,y+2),f"frame {int(index)}",fill='white')
            draw.text((x+2,y+17),f"jx {meta['rasterJitterX']:.7f}",fill='white')
        canvas.save(root/'glass-jitter-contact-sheet.png')
    return dict(status='TEMPORAL_DIAGNOSTIC',cases=out,scope='Fixed physical edge windows; recorded pixels and jitter, not subjective motion proof or a universal pass threshold',performanceAccepted=False)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('directory',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    result=evaluate(a.directory)
    with a.output.open('x',encoding='utf-8') as f:json.dump(result,f,indent=2)
    print(json.dumps([dict(name=c['name'],frames=c['frames'],uniqueJitters=c['uniqueJitters'],groups=c['groups']) for c in result['cases']],indent=2))
if __name__=='__main__':main()
