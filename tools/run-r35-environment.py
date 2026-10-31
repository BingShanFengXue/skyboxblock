#!/usr/bin/env python3
"""R35 real Brigadier commands + same-frame private canvas/GPU/config evidence, isolated new worlds only."""
from __future__ import annotations
import argparse, importlib.util
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('r35_probe_runner',ROOT/'tools/run-r30-probe.py')
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
RESET='skyboxblock environment reset all'
def set_(key,value): return f'skyboxblock environment set {key} {value}'
def phases(mode,warmup,sample,cycles,hold):
    result=[]
    def phase(name,client_commands=(),**extra):
        row=dict(name=name,panelWidth=32,panelHeight=32,panelDistance=6,orientPanel=True,y=180,pitch=-15,
            warmupSeconds=2,sampleSeconds=1,clientCommands=list(client_commands),commands=[
                'gamerule doDaylightCycle false','gamerule doWeatherCycle false','time set 6000','weather clear'])
        row.update(extra);result.append(row)
    def isolated(name,client_commands,**extra):phase(name,[RESET,*client_commands],**extra)
    phase('auto-baseline',['skyboxblock cloudheight reset','skyboxblock cloudfade reset',RESET],warmupSeconds=12)
    phase('query-list',['skyboxblock environment list','skyboxblock env get sky_color',
        'skyboxblock environment get minecraft:visual/moon_phase','skyboxblock environment get visual/cloud_height'])
    isolated('sky-red',[set_('sky_color','#FF0000'),set_('sky_fog_end_distance',32768),
        set_('fog_start_distance',16384),set_('cloud_color','#00000000')])
    isolated('fog-green',[set_('sky_color','#FF0000'),set_('fog_color','#00FF00'),
        set_('sky_fog_end_distance',1),set_('cloud_color','#00000000')])
    isolated('fog-negative-start',[set_('fog_start_distance',-64),set_('fog_end_distance',512)])
    isolated('fog-end-minimum',[set_('fog_end_distance',1)])
    isolated('independent-sky-fog',[set_('fog_end_distance',96),set_('sky_fog_end_distance',2048)])
    isolated('cloud-cyan',[set_('cloud_color','#FF00FFFF')])
    isolated('cloud-half-alpha',[set_('cloud_color','#8000FFFF')])
    isolated('cloud-transparent',[set_('cloud_color','#0000FFFF')])
    isolated('cloud-height',[set_('cloud_height',240.33)])
    isolated('cloud-height-legacy-equivalent',[set_('cloud_height',192.33)])
    isolated('cloud-fog-near',[set_('cloud_fog_end_distance',128)])
    isolated('cloud-fog-far',[set_('cloud_fog_end_distance',768)])
    isolated('dawn-magenta',[set_('sunrise_sunset_color','#FFFF00FF'),set_('cloud_color','#00000000')])
    isolated('dawn-transparent',[set_('sunrise_sunset_color','#00FF00FF'),set_('cloud_color','#00000000')])
    isolated('sun-independent',[set_('sun_angle',45),set_('moon_angle',225),set_('cloud_color','#00000000')],yaw=90,pitch=-45)
    isolated('moon-independent',[set_('sun_angle',225),set_('moon_angle',45),set_('cloud_color','#00000000')],yaw=90,pitch=-45)
    for index,name in enumerate(['full_moon','waning_gibbous','third_quarter','waning_crescent',
            'new_moon','waxing_crescent','first_quarter','waxing_gibbous']):
        isolated('moon-phase-'+name,[set_('sun_angle',225),set_('moon_angle',45),
            set_('moon_phase',name),set_('cloud_color','#00000000')],yaw=90,pitch=-45)
    isolated('stars-bright',[set_('star_angle',0),set_('star_brightness',1),set_('cloud_color','#00000000')])
    isolated('stars-rotated',[set_('star_angle',90),set_('star_brightness',1),set_('cloud_color','#00000000')])
    isolated('stars-hidden',[set_('star_brightness',0),set_('cloud_color','#00000000')])
    isolated('stars-rain-overridden',[set_('star_brightness',1),set_('cloud_color','#00000000')],
        commands=['gamerule doDaylightCycle false','gamerule doWeatherCycle false','time set 6000','weather rain'],warmupSeconds=8)
    isolated('clear-restored',[],warmupSeconds=8)
    isolated('angles-large-negative',[set_('sun_angle',-720.25),set_('moon_angle',1080),set_('star_angle','3.4028235E38')])
    occlusion=[set_('sun_angle',120),set_('moon_angle',120),set_('star_brightness',1),
        set_('fog_color','#203040'),set_('cloud_color','#00000000')]
    isolated('occluder-off',[*occlusion,set_('has_sky_occluder','false')],yaw=90,pitch=30)
    isolated('occluder-on',[*occlusion,set_('has_sky_occluder','true')],yaw=90,pitch=30)
    values={'sky_color':'#3366CC','fog_color':'#203040','fog_start_distance':-32,'fog_end_distance':768,
        'sky_fog_end_distance':1024,'cloud_color':'#8044CCFF','cloud_height':240.33,
        'cloud_fog_end_distance':512,'sunrise_sunset_color':'#80FF8040','sun_angle':20,
        'moon_angle':210,'star_angle':120,'moon_phase':'first_quarter','star_brightness':0.75,'has_sky_occluder':'true'}
    phase('all-persisted',[RESET,*[set_(key,value) for key,value in values.items()]],expected=values)
    phase('reload-persisted',['skyboxblock reload'],expected=values)
    invalid=['skyboxblock env set sky_color #GG0000','skyboxblock environment set fog_end_distance 0',
        'skyboxblock environment set sky_fog_end_distance -1','skyboxblock environment set cloud_fog_end_distance 4097',
        'skyboxblock environment set cloud_height NaN','skyboxblock environment set sun_angle Infinity',
        'skyboxblock environment set star_brightness 1.1','skyboxblock environment set has_sky_occluder yes',
        'skyboxblock environment set moon_phase 8','skyboxblock environment set bogus 1',
        'skyboxblock environment set sky_color #112233 extra']
    phase('invalid-no-mutation',invalid,allowFailedClientCommand=True,expected=values,expectedCommandResult=0)
    phase('reset-one',['skyboxblock env reset minecraft:visual/sky_color'])
    phase('legacy-shadow',[set_('cloud_height',240.33),'skyboxblock cloudheight 200',
        set_('cloud_fog_end_distance',512),'skyboxblock clouddistance 768'])
    phase('legacy-restored',[RESET])
    # Other eight block styles must be unaffected by even extreme vanilla-only overrides.
    for style in ['white','black','kloppenheim','syferfontein','dusk','galaxy','lush','apocalypse']:
        block=f'skyboxblock:{style}_sky_block'
        phase('style-'+style+'-auto',[RESET],material=block)
        phase('style-'+style+'-overridden',[set_('sky_color','#FF0000'),set_('fog_color','#00FF00'),
            set_('cloud_color','#FFFF00FF'),set_('has_sky_occluder','true')],material=block)
    phase('reset-final',['skyboxblock cloudheight reset','skyboxblock cloudfade reset',RESET])
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,required=True)
    p.add_argument('--label',required=True);p.add_argument('--profile',choices=['vanilla','iris','dh','iris-dh'],default='vanilla')
    p.add_argument('--shader');p.add_argument('--fsr',type=int,default=-1);p.add_argument('--timeout',type=float,default=900)
    a=p.parse_args();runner.phases=phases
    args=['--mode','smoke','--profile',a.profile,'--terrain','flat','--render-distance','8',
        '--probe',str(ROOT/'build/libs/skybox-validation-probe-r35.jar'),'--jar',str(a.jar),
        '--label',a.label,'--timeout',str(a.timeout)]
    if a.shader:args+=['--shader',a.shader,'--fsr',str(a.fsr)]
    return runner.main(args)
if __name__=='__main__':raise SystemExit(main())