#!/usr/bin/env python3
"""Check actual R35 client evidence, including remapped Vec3 fields and sparse star pixels."""
from __future__ import annotations
import argparse,hashlib,json,math
from pathlib import Path
import numpy as np
from PIL import Image

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('instance',type=Path);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();root=a.instance/'r30-results';checks=[]
    def check(name,ok,**evidence):checks.append(dict(name=name,passed=bool(ok),**evidence))
    run=json.loads((a.instance/'run-result.json').read_text(encoding='utf-8'))
    summary=json.loads((root/'run-summary.json').read_text(encoding='utf-8'));phases=summary['phases'];rows={v['name']:v for v in phases}
    check('run_complete',run['exitCode']==0 and run['complete'] and run['phases']==run['expectedPhases'] and not run['timedOut'],actual=run)
    # Preserve the exact auth-service error from these offline fixtures; all other ERRORs fail.
    offline_auth=[x for x in run['errors'] if '[Yggdrasil Key Fetcher/ERROR]: Failed to request yggdrasil public key' in x]
    unexpected=[x for x in run['errors'] if x not in offline_auth]
    check('no_unexpected_client_errors',not unexpected and not run['fatalErrors'],unexpected=unexpected,offlineAuthWarnings=offline_auth)
    check('actual_jar_unchanged',run['inputBytesUnchanged'])
    for row in phases:
        name=row['name'];commands=row['clientCommandResults'];expected=row['request'].get('expectedCommandResult',1)
        check(name+':commands',len(commands)==len(row['request']['clientCommands']) and all(c['result']==expected for c in commands),results=commands)
        check(name+':has_real_block_geometry',row['minimumCollectedBlocks']>0 and row['measurementFrames']>0,
            minimumBlocks=row['minimumCollectedBlocks'],frames=row['measurementFrames'])
        check(name+':no_gl_errors',not row['phaseEndGlErrors'] and not row['diagnosticGlErrors'])
        audit=row['privateStateAudit'];check(name+':state_restored',audit.get('enabled') is True and audit.get('failedTransactions')==0,audit=audit)
        for key,wanted in row['request'].get('expected',{}).items():
            actual=row['environmentState'].get('configured',{}).get(key)
            if key.endswith('color'):
                bits=int(wanted[1:],16) | (0xff000000 if len(wanted)==7 else 0)
                ok=actual is not None and (int(actual)&0xffffffff)==bits
            elif key=='moon_phase':ok=actual==6
            elif key=='has_sky_occluder':ok=actual==(wanted=='true')
            else:ok=isinstance(actual,(int,float)) and math.isclose(actual,float(wanted),rel_tol=1e-5,abs_tol=1e-4)
            check(name+':persisted:'+key,ok,actual=actual,expected=wanted)
            saved=row['cloudState'].get('persistedProperties',{}).get('environment.visual/'+key)
            check(name+':saved_key:'+key,saved is not None,actual=saved)
    def state(name):return rows[name]['environmentState']
    def gpu(name):return state(name)['backgroundGpu']
    def cloud(name):return rows[name]['cloudState']
    def rgb(name,key):
        value=state(name)[key]
        if set(value)=={'x','y','z'}:return [value['x'],value['y'],value['z']]
        # Minecraft 1.21.1 intermediary fields of Vec3, in explicit x/y/z order.
        if set(value)=={'field_1352','field_1351','field_1350'}:return [value['field_1352'],value['field_1351'],value['field_1350']]
        raise ValueError('Unrecognized Vec3 schema: '+str(value))
    check('sky_color_exact_snapshot',rgb('sky-red','skyColor')==[1.,0.,0.])
    check('fog_color_exact_snapshot',rgb('fog-green','fogColor')==[0.,1.,0.])
    check('negative_fog_start_gpu',gpu('fog-negative-start')['FogStart']==-64)
    check('minimum_fog_end_gpu',gpu('fog-end-minimum')['FogEnd']==96)
    check('independent_sky_end_gpu',gpu('independent-sky-fog')['FogEnd']==2048)
    check('cloud_cyan_exact_snapshot',rgb('cloud-cyan','cloudColor')==[0.,1.,1.])
    check('cloud_alpha_gpu',math.isclose(cloud('cloud-half-alpha')['CloudOpacity'],128/255,abs_tol=1e-6))
    check('cloud_zero_alpha_state',state('cloud-transparent')['cloudOpacity']==0)
    for name,end in [('cloud-fog-near',128),('cloud-fog-far',768)]:
        check(name+':gpu_fade',cloud(name)['CloudFogEnd']==end and math.isclose(cloud(name)['CloudFogStart'],end/3,abs_tol=1e-4))
    for index,name in enumerate(['full_moon','waning_gibbous','third_quarter','waning_crescent',
            'new_moon','waxing_crescent','first_quarter','waxing_gibbous']):
        check('moon-phase-'+name+':ordinal',state('moon-phase-'+name)['moonPhase']==index)
    check('dawn_argb_snapshot',state('dawn-magenta')['sunrise']==[1.,0.,1.,1.] and state('dawn-transparent')['sunrise']==[1.,0.,1.,0.])
    check('star_override_ignores_weather_gpu',state('stars-rain-overridden')['rain']>.9 and
        state('stars-rain-overridden')['starsGpuDrawColor']==[1.,1.,1.,1.],actual=state('stars-rain-overridden'))
    check('state_audit_really_sampled',phases[-1]['privateStateAudit']['sampledTransactions']>0)
    check('reset_one_only',state('reset-one')['configured']['sky_color']=='auto' and state('reset-one')['configured']['star_brightness']==.75)
    check('legacy_keys_survive_environment_reset',cloud('legacy-restored')['vanillaCloudHeight']==200 and
        cloud('legacy-restored')['vanillaCloudFadeEnd']==768 and all(v=='auto' for v in state('legacy-restored')['configured'].values()))
    check('reset_final_is_auto',all(v=='auto' for v in state('reset-final')['configured'].values()))
    for key in ['skyColor','fogColor','cloudColor','starBrightness','moonPhase','phase','rain','sunrise','cloudOpacity']:
        before=state('auto-baseline').get(key) if key=='sunrise' else state('auto-baseline')[key]
        after=state('reset-final').get(key) if key=='sunrise' else state('reset-final')[key]
        check('default_restored:'+key,abs(before-after)<1e-4 if key=='phase' else before==after,before=before,after=after)
    def image(name,private=False):
        row=rows[name];stem=f"{row['index']:02d}-{name}"
        if private:
            refs=row['privateSkyReferences'];key='displayTarget' if 'displayTarget' in refs else 'target';file=root/refs[key]['file']
        else:file=root/(stem+'.png')
        return np.asarray(Image.open(file).convert('RGB'),dtype=np.float32)
    red=image('sky-red',True);green=image('fog-green',True)
    red_pixels=np.mean((red[:,:,0]>250)&(red[:,:,1]<3)&(red[:,:,2]<3))
    green_pixels=np.mean((green[:,:,0]<3)&(green[:,:,1]>250)&(green[:,:,2]<3))
    check('sky_red_actual_pixels',red_pixels>.15,coverage=float(red_pixels))
    check('fog_green_actual_pixels',green_pixels>.9,coverage=float(green_pixels))
    def difference(first,second,private=True):
        x,y=image(first,private),image(second,private);h,w=x.shape[:2];sl=np.s_[h//4:h*3//4,w//4:w*3//4]
        return np.abs(x[sl]-y[sl])
    def delta(first,second,private=True):return float(difference(first,second,private).mean())
    check('cloud_zero_alpha_actual_clear_canvas',delta('cloud-transparent','dawn-transparent')<.01,
        mae=delta('cloud-transparent','dawn-transparent'),meaning='Two independent no-cloud configurations; depth-skip is additionally checked in source/Java tests, not inferred from cumulative draw counters.')
    check('dawn_alpha_actual_pixels',delta('dawn-magenta','dawn-transparent')>.1,mae=delta('dawn-magenta','dawn-transparent'))
    check('sun_moon_independent_actual_pixels',delta('sun-independent','moon-independent')>.1,mae=delta('sun-independent','moon-independent'))
    # Stars are sparse: require actual changed high-contrast pixels instead of diluting them into a screen-average threshold.
    for label,second in [('angle','stars-rotated'),('brightness','stars-hidden')]:
        diff=difference('stars-bright',second);count=int(np.sum(np.max(diff,axis=2)>8))
        check('stars_'+label+'_actual_pixels',count>=20,changedPixelsOver8=count,mae=float(diff.mean()))
    check('occluder_actual_pixels',delta('occluder-off','occluder-on')>.1,mae=delta('occluder-off','occluder-on'))
    moon_hashes={hashlib.sha256(image('moon-phase-'+name,True).tobytes()).hexdigest() for name in
        ['full_moon','waning_gibbous','third_quarter','waning_crescent','new_moon','waxing_crescent','first_quarter','waxing_gibbous']}
    check('moon_eight_distinct_actual_canvases',len(moon_hashes)==8,distinct=len(moon_hashes))
    for style in ['white','black','kloppenheim','syferfontein','dusk','galaxy','lush','apocalypse']:
        error=delta('style-'+style+'-auto','style-'+style+'-overridden',False)
        check('other_style_unchanged:'+style,error<1,centerCropMae=error)
    report=dict(passed=sum(c['passed'] for c in checks),total=len(checks),failed=[c for c in checks if not c['passed']],checks=checks,
        instance=str(a.instance),productionJarSha256=run['productionJarSha256'],profile=summary.get('profile'),offlineAuthWarnings=offline_auth)
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    print(json.dumps({k:report[k] for k in ['passed','total','failed','instance']},indent=2,ensure_ascii=False))
    return 0 if report['passed']==report['total'] else 1
if __name__=='__main__':raise SystemExit(main())