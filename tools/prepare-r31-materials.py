#!/usr/bin/env python3
"""Deterministic R31 user-material conversion, seam repair and resource generation.
Does not modify the supplied originals or any installed game instance.
"""
from pathlib import Path
import hashlib
import itertools
import json
import math
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT.parent / 'skyboxblock素材' / '04-定稿'
RES = ROOT / 'src/main/resources'
ASSETS = RES / 'assets/skyboxblock'
REPORT = ROOT / 'build/reports/r31-material-assets.json'
STYLES = [
    ('white', 'white_sky_block', '纯白', 'Pure White', 'white_dye'),
    ('black', 'black_sky_block', '纯黑', 'Pure Black', 'black_dye'),
    ('kloppenheim', 'kloppenheim_sky_block', '晴空·A', 'Clear Sky A', 'light_blue_dye'),
    ('syferfontein', 'syferfontein_sky_block', '晴空·B', 'Clear Sky B', 'blue_dye'),
    ('dusk', 'dusk_sky_block', '黄昏', 'Dusk', 'orange_dye'),
    ('galaxy', 'galaxy_sky_block', '银河', 'Milky Way', 'purple_dye'),
    ('lush', 'lush_sky_block', '葱郁行星', 'Lush Planet', 'green_dye'),
    ('apocalypse', 'apocalypse_sky_block', '末日陆地', 'Apocalypse Land', 'red_dye'),
]

def dump(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def hdr(path):
    with path.open('rb') as f:
        header=[]
        while True:
            line=f.readline()
            if not line: raise ValueError('HDR missing resolution')
            header.append(line)
            if line.strip().startswith((b'-Y',b'+Y')): break
        parts=line.split(); h=int(parts[1]); w=int(parts[3])
        assert parts[0]==b'-Y' and parts[2]==b'+X', parts
        result=np.empty((h,w,4), dtype=np.uint8)
        for y in range(h):
            head=f.read(4)
            if head[:2]!=b'\x02\x02' or ((head[2]<<8)|head[3])!=w:
                remaining=head+f.read(w*h*4-4)
                if y: raise ValueError('Unsupported mixed HDR scanline')
                result[:]=np.frombuffer(remaining,dtype=np.uint8).reshape(h,w,4)
                break
            for channel in range(4):
                x=0
                while x<w:
                    n=f.read(1)[0]
                    if n>128:
                        n-=128; result[y,x:x+n,channel]=f.read(1)[0]
                    else:
                        result[y,x:x+n,channel]=np.frombuffer(f.read(n),dtype=np.uint8)
                    x+=n
                assert x==w
        linear=result[...,:3].astype(np.float32)*np.exp2(result[...,3:4].astype(np.float32)-136)
        linear[result[...,3]==0]=0
        lum=linear@np.array([.2126,.7152,.0722])
        exposure=.45/max(float(np.median(lum)),1e-6)
        mapped=linear*exposure
        mapped=mapped/(1+mapped)
        srgb=np.where(mapped<=.0031308,12.92*mapped,1.055*mapped**(1/2.4)-.055)
        return np.uint8(np.clip(srgb*255+.5,0,255)), {'exposure':exposure,'linearMedianLuminance':float(np.median(lum)),'operator':'global Reinhard + sRGB; static LDR display, no runtime HDR exposure'}

# Standard cube coordinates: front +Z, back -Z, right +X, left -X, up +Y, down -Y.
def face_direction(face,u,v):
    one=np.ones_like(u)
    return np.stack({'front':(u,-v,one),'back':(-u,-v,-one),'right':(one,-v,-u),
                     'left':(-one,-v,u),'up':(u,one,v),'down':(u,-one,-v)}[face],axis=-1)

def face_uv(face,d):
    x,y,z=np.moveaxis(d,-1,0)
    if face=='front': return x/np.abs(z),-y/np.abs(z),z
    if face=='back': return -x/np.abs(z),-y/np.abs(z),-z
    if face=='right': return -z/np.abs(x),-y/np.abs(x),x
    if face=='left': return z/np.abs(x),-y/np.abs(x),-x
    if face=='up': return x/np.abs(y),z/np.abs(y),y
    return x/np.abs(y),-z/np.abs(y),-y

def bilinear(a,u,v):
    h,w=a.shape[:2]; x=np.clip((u+1)*.5*(w-1),0,w-1); y=np.clip((v+1)*.5*(h-1),0,h-1)
    x0=x.astype(int); y0=y.astype(int); x1=np.minimum(x0+1,w-1); y1=np.minimum(y0+1,h-1)
    wx=(x-x0)[...,None]; wy=(y-y0)[...,None]
    return (a[y0,x0]*(1-wx)+a[y0,x1]*wx)*(1-wy)+(a[y1,x0]*(1-wx)+a[y1,x1]*wx)*wy

def cube_panorama():
    names=['front','right','back','left','up','down']
    paths={n:SOURCE/'16_末日陆地_ApocalypseLand'/('末日陆地_'+n+'.png') for n in names}
    arrays={n:np.array(Image.open(p).convert('RGB'),dtype=np.float32) for n,p in paths.items()}
    variants={n:[np.rot90(a,k) for k in range(4)] for n,a in arrays.items()}
    # Establish all 12 actual directional edge correspondences, without assumptions about top/bottom rotations.
    pairs=[]; t=np.linspace(-.98,.98,192)
    for i,a in enumerate(names):
        for j,b in enumerate(names[i+1:],i+1):
            samples=None
            for axis,sign in ((0,-1),(0,1),(1,-1),(1,1)):
                u=t if axis==1 else np.full_like(t,sign)
                v=t if axis==0 else np.full_like(t,sign)
                d=face_direction(a,u,v); ub,vb,depth=face_uv(b,d)
                if np.all(depth>0) and np.max(np.maximum(np.abs(ub),np.abs(vb)))<1.00001:
                    samples=(u,v,ub,vb); break
            if samples is None: continue
            u,v,ub,vb=samples
            scores=np.zeros((4,4))
            for ra,rb in itertools.product(range(4),repeat=2):
                scores[ra,rb]=np.mean(np.abs(bilinear(variants[a][ra],u,v)-bilinear(variants[b][rb],ub,vb)))
            pairs.append((i,j,scores))
    assert len(pairs)==12,len(pairs)
    best=min(itertools.product(range(4),repeat=6),key=lambda r:sum(s[r[i],r[j]] for i,j,s in pairs))
    oriented={n:variants[n][best[i]] for i,n in enumerate(names)}
    w,h=2048,1024; output=np.empty((h,w,3),np.float32)
    # Blend only a 2-texel directional neighborhood at the original cube joins.
    for start in range(0,h,64):
        y=np.arange(start,min(h,start+64)); x=np.arange(w)
        theta=((x+.5)/w-.5)*2*np.pi; phi=(y+.5)/h*np.pi
        d=np.stack(np.broadcast_arrays(np.sin(phi[:,None])*np.cos(theta),np.cos(phi[:,None]),np.sin(phi[:,None])*np.sin(theta)),axis=-1)
        maximum=np.max(np.abs(d),axis=-1); total=np.zeros(d.shape[:2]); rgb=np.zeros(d.shape,np.float64)
        for n,a in oriented.items():
            with np.errstate(divide='ignore',invalid='ignore'): u,v,depth=face_uv(n,d)
            weight=np.clip((depth/maximum-(1-.012))/.024,0,1)
            weight=weight*weight*(3-2*weight)
            mask=weight>0
            if mask.any():
                rgb[mask]+=bilinear(a,u[mask],v[mask])*weight[mask,None]; total[mask]+=weight[mask]
        assert np.all(total>0)
        output[start:start+len(y)]=rgb/total[...,None]
    return np.uint8(np.clip(output+.5,0,255)), {'cubeRotationsCCW':dict(zip(names,map(int,best))),
            'cubeRawEdgeMeanAbsoluteRGB':float(sum(s[best[i],best[j]] for i,j,s in pairs)/12),
            'cubeEdges':12,'cubeBlendTexels':3.072,'sources':[{'path':str(p.relative_to(SOURCE)),'sha256':sha(p)} for p in paths.values()]}

def periodic(a):
    a=a.astype(np.float32); h,w=a.shape[:2]
    original=float(np.mean(np.abs(a[:,0]-a[:,-1])))
    # The shared seam is a single color per latitude with a smooth narrow shoulder.
    band=max(4,min(16,w//256)); shared=(a[:,0]+a[:,-1])/2
    for k in range(band):
        weight=(1-k/band)**2
        a[:,k]=a[:,k]*(1-weight)+shared*weight
        a[:,-1-k]=a[:,-1-k]*(1-weight)+shared*weight
    # A spherical pole is one point, not an entire longitude strip.
    pole=max(2,min(6,h//256))
    for edge in (0,h-1):
        shared=a[edge].mean(axis=0)
        for k in range(pole):
            row=k if edge==0 else h-1-k; weight=(1-k/pole)**2
            a[row]=a[row]*(1-weight)+shared*weight
    a=np.uint8(np.clip(a+.5,0,255))
    return a,{'sourceWrapMeanAbsoluteRGB':original,'wrapMaxRGB':int(np.abs(a[:,0].astype(int)-a[:,-1].astype(int)).max()),
              'northPoleMaxRGBRange':int(np.ptp(a[0].astype(int),axis=0).max()),'southPoleMaxRGBRange':int(np.ptp(a[-1].astype(int),axis=0).max()),'seamBandPixels':band,'poleBandPixels':pole}

def main():
    (ASSETS/'textures/sky').mkdir(parents=True,exist_ok=True)
    report={'materials':[],'sourceDirectory':str(SOURCE),'inputsUnmodified':True}
    inputs={'kloppenheim':'kloppenheim_06_puresky_4k.hdr','syferfontein':'syferfontein_6d_clear_puresky_4k.hdr',
        'dusk':'天空_04_黄昏_qwantani_dusk_2_4096x2048.jpg','galaxy':'宇宙_01_银河全景_SolarSystemScope_8192x4096_提亮版.jpg','lush':'行星_Lush_02.png'}
    previews=[]
    for name,block,zh,en,dye in STYLES:
        info={'style':name,'block':'skyboxblock:'+block,'displayName':'天空盒方块（'+zh+'）'}
        if name in ('white','black'):
            a=np.full((32,32,3),255 if name=='white' else 0,dtype=np.uint8)
        else:
            if name=='apocalypse': a,extra=cube_panorama(); info.update(extra)
            else:
                path=SOURCE/inputs[name]; info.update(source=inputs[name],sourceSha256=sha(path))
                if path.suffix=='.hdr': a,extra=hdr(path); info.update(extra)
                else:
                    im=Image.open(path).convert('RGB')
                    if im.width>4096: im=im.resize((4096,2048),Image.Resampling.LANCZOS)
                    a=np.array(im)
            a,seams=periodic(a); info.update(seams)
            path=ASSETS/'textures/sky'/f'{name}.png'; Image.fromarray(a).save(path,optimize=True)
            info.update(width=a.shape[1],height=a.shape[0],outputSha256=sha(path))
        im=Image.fromarray(a)
        thumb=im.resize((320,160),Image.Resampling.LANCZOS) if name not in ('white','black') else im.resize((320,160))
        tile=Image.new('RGB',(320,190),'#202631'); tile.paste(thumb,(0,0)); ImageDraw.Draw(tile).text((8,168),name,fill='white'); previews.append(tile)
        if name in ('white','black'): icon=im.resize((32,32))
        else:
            h,w=a.shape[:2]; icon=im.crop((w//2-h//2,h//4,w//2+h//2,h//4+h//2)).resize((32,32),Image.Resampling.LANCZOS)
        icon.save(ASSETS/'textures/block'/f'{block}.png')
        dump(ASSETS/'blockstates'/f'{block}.json',{'variants':{'':{'model':'skyboxblock:block/'+block}}})
        dump(ASSETS/'models/block'/f'{block}.json',{'parent':'minecraft:block/cube_all','textures':{'all':'skyboxblock:block/'+block}})
        dump(ASSETS/'models/item'/f'{block}.json',{'parent':'skyboxblock:block/'+block})
        dump(RES/'data/skyboxblock/loot_table/blocks'/f'{block}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'skyboxblock:'+block}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
        dump(RES/'data/skyboxblock/recipe'/f'{block}.json',{'type':'minecraft:crafting_shapeless','category':'building','ingredients':[{'item':'skyboxblock:sky_block'},{'item':'minecraft:'+dye}],'result':{'id':'skyboxblock:'+block,'count':1}})
        report['materials'].append(info)
    for locale in ('zh_cn','en_us'):
        path=ASSETS/'lang'/f'{locale}.json'; data=json.loads(path.read_text(encoding='utf-8'))
        data['itemGroup.skyboxblock.sky']='天空盒方块' if locale=='zh_cn' else 'Sky Box Blocks'
        data['block.skyboxblock.sky_block']='天空盒方块（原版）' if locale=='zh_cn' else 'Sky Box Block (Vanilla)'
        for name,block,zh,en,dye in STYLES: data['block.skyboxblock.'+block]='天空盒方块（'+zh+'）' if locale=='zh_cn' else 'Sky Box Block ('+en+')'
        dump(path,data)
    dump(RES/'data/minecraft/tags/block/mineable/pickaxe.json',{'replace':False,'values':['skyboxblock:sky_block']+['skyboxblock:'+x[1] for x in STYLES]})
    sheet=Image.new('RGB',(1280,380),'#202631')
    for i,im in enumerate(previews): sheet.paste(im,((i%4)*320,(i//4)*190))
    preview=ROOT/'build/reports/r31-material-overview.png'; preview.parent.mkdir(parents=True,exist_ok=True); sheet.save(preview)
    dump(REPORT,report)
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__': main()
