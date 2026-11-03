"""Read-only installed-pack/bytecode contract checks; no game/fixture/Gradle execution.
Run: python src/test/python/itrp_scene_contract.py [shaderpack.zip] [iris.jar]
"""
from pathlib import Path
import hashlib
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[3]
INSTANCE = Path(r'D:\Program Files\Game\Minecraft\PCL\.minecraft\versions\26.2-Fabric 0.20.1')
PACK = Path(sys.argv[1]) if len(sys.argv) > 1 else INSTANCE / 'shaderpacks/iterationRP Alpha 0.8.31.zip'
IRIS = Path(sys.argv[2]) if len(sys.argv) > 2 else INSTANCE / 'mods/iris-fabric-1.11.4+mc26.2.jar'
JAVAP = Path(r'C:\Program Files\Java\jdk-25.0.4.1\bin\javap.exe')
EXPECTED = '334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8'
checks = 0


def check(condition, label):
    global checks
    checks += 1
    assert condition, label
    print('PASS:', label)


def javap(name):
    return subprocess.run([str(JAVAP), '-private', '-c', '-classpath', str(IRIS), name],
                          check=True, capture_output=True, text=True, encoding='utf-8').stdout


def main():
    check(hashlib.sha256(PACK.read_bytes()).hexdigest() == EXPECTED, 'installed ITRP archive is exact audited hash')
    with zipfile.ZipFile(PACK) as z:
        def text(name):
            return z.read('shaders/' + name).decode('utf-8').replace('\r', '')
        settings = text('Lib/Settings.glsl')
        check(re.search(r'#define\s+FBTEX_MAIN_OUTPUT\s+colortex6', settings), 'scene-input semantic is colortex6, not albedo colortex0')
        check(re.search(r'#define\s+FBTEX_GSOLID_DATA\s+colortex1', settings)
              and re.search(r'#define\s+FBTEX_GSOLID_NORMAL\s+colortex2', settings), 'neutral MRT target semantics match real source')
        check(not any(re.fullmatch(r'shaders/deferred\d*\.fsh', n) for n in z.namelist()), 'generic post-deferred albedo write is insufficient for this actual pack')
        check('Soild_FS.glsl' in text('composite25.fsh') and 'WaterRefraction_FS.glsl' in text('composite40.fsh')
              and 'Translucent_FS.glsl' in text('composite50.fsh'), 'actual solid/water/glass program order is composite25/40/50')
        solid = text('Lib/Programs/Composite/Soild_FS.glsl')
        water = text('Lib/Programs/Composite/WaterRefraction_FS.glsl')
        glass = text('Lib/Programs/Composite/Translucent_FS.glsl')
        check('RENDERTARGETS: 6' in solid and 'RENDERTARGETS: 6' in water, 'solid and water use scene MRT6')
        check('color = textureLod(FBTEX_MAIN_OUTPUT, sampleCoord, 0.0).rgb;' in glass
              and 'color = textureLod(FBTEX_MAIN_OUTPUT, refractCoord, 0.0).rgb;' in glass,
              'actual GlassRefraction displaced samples consume scene input')
        check('RENDERTARGETS: 0' in glass and 'imageStore(FBIMG_SST_TEMPORAL' in glass
              and re.search(r'#define\s+FBIMG_SST_TEMPORAL\s+colorimg9', settings), 'ordinary glass branch stores image9 instead of guessed MRT6')
        utility = text('Lib/Utilities.glsl')
        check('return vec2(u >> 8u, u & 255u) / 255.0;' in utility
              and 'float(u & 127u)' in utility and re.search(r'#define\s+MATID_LAND\s+1\.0', settings),
              'packed neutral parallax/material/lightmap uses official unpack semantics')
        check((65281 >> 8, 65281 & 127, 255 >> 8, 255 & 255) == (255, 1, 0, 255), 'neutral words decode to parallax1/LAND1/block0/sky1')
    pipeline = javap('net.irisshaders.iris.pipeline.IrisRenderingPipeline')
    begin = pipeline.split('public void beginTranslucents();', 1)[1].split('public void finalizeLevelRendering();', 1)[0]
    check(begin.index('copyPreTranslucentDepth') < begin.index('CompositeRenderer.renderAll'), 'actual Iris pre-translucent depth copy precedes deferred renderer')
    composite = javap('net.irisshaders.iris.pipeline.CompositeRenderer')
    render = composite.split('public void renderAll();', 1)[1].split('private ', 1)[0]
    check(render.index('Program.use') < render.index('CustomUniforms.push') < render.index('GlStateManager._drawElements:(IIIJ)V'), 'Iris1.11.4 hook is after program/sampler use and before real indexed draw')
    check('renderQuad' not in render, 'old Iris1.8 renderQuad hook is absent and must not be copied')
    targets = javap('net.irisshaders.iris.targets.RenderTargets')
    check('com.mojang.blaze3d.textures.GpuTexture getDepthTextureNoTranslucents()' in targets, '26.2 actual no-translucent depth accessor is MC GpuTexture')
    bridge = (ROOT / 'src/main/java/dev/yg/skyboxblock/client/ItrpSceneBridge.java').read_text(encoding='utf-8')
    check(EXPECTED in bridge and '1.11.4' in bridge, 'production bridge locks the observed pack and Iris version')
    check(not re.search(r'GL(?:4[0-9])\.gl', bridge), 'bridge introduces no unguarded GL40+ function calls')
    images = javap('net.irisshaders.iris.gl.image.ImageBinding')
    update = images.split('public void update();', 1)[1]
    check(re.search(r'iconst_0\s+\d+: iconst_1\s+\d+: iconst_0', update)
          and 'IrisRenderSystem.bindImageTexture:(IIIZIII)V' in update,
          'Iris1.11.4 binds mip0/layered=true/layer0 even for a 2D image')
    check('GL42.GL_IMAGE_BINDING_LAYERED,imageUnit)==1' in bridge
          and 'exposure-image-is-not-texture2D' in bridge,
          'production verifies real image flags plus underlying 2D texture ownership')
    print(f'ItrpSceneContract: {checks} checks passed')


if __name__ == '__main__':
    main()
