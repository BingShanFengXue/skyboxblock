#!/usr/bin/env python3
"""Visual check for the R38 custom sky styles (config folder -> registered blocks)."""
from __future__ import annotations
import argparse, importlib.util, shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('r38_probe', ROOT / 'tools/run-r30-probe.py')
runner = importlib.util.module_from_spec(spec); spec.loader.exec_module(runner)

CUSTOM_ID = 'skyboxblock:test_sky_block_1'
PANORAMA = ROOT / 'src/main/resources/assets/skyboxblock/textures/sky/galaxy.png'
CONFIG = """# fixture for the R38 custom-style test
enable = true

test_sky_block_1 = {
    id = "skyboxblock:test_sky_block_1"
    picture = "测试-全景.png"
    creative_inventory = true
    lang_zh_cn = { name = "测试天空盒1" }
    lang_en_us = { name = "Test Sky Block 1" }
}
"""


def _offline_uuid(name: str) -> str:
    import hashlib, uuid
    digest = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def prepare_fixture(instance, config):
    """Write the studio folder exactly where the mod reads it, before the client starts."""
    studio = config / 'skyboxblock' / 'skyboxes'
    studio.mkdir(parents=True, exist_ok=True)
    (studio / '00_test.conf').write_text(CONFIG, encoding='utf-8')
    shutil.copy2(PANORAMA, studio / '测试-全景.png')
    # The fixture player must own permission level 2 for the per-player subtree to execute.
    import json
    ops = [{"uuid": _offline_uuid("SkyboxR30"), "name": "SkyboxR30", "level": 4,
            "bypassesPlayerLimit": True}]
    (instance / 'ops.json').write_text(json.dumps(ops, indent=2), encoding='utf-8')



def phases(mode, warmup, sample, cycles, hold):
    base = ['gamerule doDaylightCycle false', 'gamerule doWeatherCycle false',
            'time set 6000', 'weather clear']
    rows = []
    for name, material in [('custom-style', CUSTOM_ID),
                           ('builtin-control', 'skyboxblock:galaxy_sky_block')]:
        rows.append(dict(name=name, panelWidth=36, panelHeight=36, panelDistance=6, orientPanel=True,
                         y=180, pitch=-12.0, yaw=0.0, material=material,
                         warmupSeconds=12 if not rows else 4, sampleSeconds=2,
                         captureFrames=2, captureEveryFrames=8,
                         commands=base + ['give @s skyboxblock:test_sky_block_1 1',
                                          'skyboxblock player @s set visual/sky_color #22FF00',
                                          'skyboxblock player @s show'] if name == 'custom-style' else base))
    return rows


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, required=True); p.add_argument('--label', required=True)
    p.add_argument('--probe', type=Path, default=ROOT / 'build/libs/skybox-validation-probe-r38.jar')
    a = p.parse_args()
    runner.phases = phases
    runner.prepare_fixture = prepare_fixture
    return runner.main(['--mode', 'smoke', '--profile', 'vanilla', '--terrain', 'flat',
                        '--render-distance', '8', '--probe', str(a.probe), '--jar', str(a.jar),
                        '--label', a.label, '--timeout', '600'])


if __name__ == '__main__':
    raise SystemExit(main())
