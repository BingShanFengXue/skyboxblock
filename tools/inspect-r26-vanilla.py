#!/usr/bin/env python3
"""Read the actual compile-classpath Minecraft sky shaders and fog bytecode."""
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT=Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
from local_paths import java_home  # noqa: E402

JDK=java_home()
cp=(ROOT/'build/compile-classpath.txt').read_text(encoding='utf-8').strip()
report=ROOT/'build/r26-vanilla-shader-reference.txt'
with report.open('x',encoding='utf-8') as out:
    for entry in cp.split(';'):
        p=Path(entry)
        if not p.is_file() or 'minecraft' not in p.name.lower(): continue
        with zipfile.ZipFile(p) as z:
            for name in ['assets/minecraft/shaders/core/position.vsh','assets/minecraft/shaders/core/position.fsh','assets/minecraft/shaders/core/position_tex.vsh','assets/minecraft/shaders/core/position_tex.fsh','assets/minecraft/shaders/include/fog.glsl']:
                if name in z.namelist():
                    out.write('\n=== '+str(p)+'!'+name+' ===\n'+z.read(name).decode('utf-8')+'\n')
for cls in ['net.minecraft.client.renderer.FogRenderer','com.mojang.blaze3d.systems.RenderSystem']:
    dest=ROOT/('build/r26-'+cls.rsplit('.',1)[-1]+'.javap.txt')
    with dest.open('xb') as out:
        subprocess.run([str(JDK/'bin/javap.exe'),'-classpath',cp,'-p','-c',cls],stdout=out,stderr=subprocess.STDOUT,check=True)
print(report)
