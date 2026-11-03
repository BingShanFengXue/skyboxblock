#!/usr/bin/env python3
"""Isolated 26.2 live regression; never changes the user's PCL instance.

prepare --name baseline [--mod path] [--dh]
launch --name baseline
call --name baseline --json '{"commands":["time set 6000"],"capture":"day.png"}'
The fixture must be compiled first. Every response and screenshot stays in build/render-regression/.
"""
from __future__ import annotations
import argparse
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import uuid

PROJECT = Path(__file__).resolve().parents[1]
ROOT = PROJECT / 'build' / 'render-regression'
SOURCE = Path(r'D:\Program Files\Game\Minecraft\PCL\.minecraft\versions\26.2-Fabric 0.20.1')
JAVA = Path(r'C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe')
FIXTURE = PROJECT / 'build' / 'fixture262-control.jar'
PACKS = ['iterationRP Alpha 0.8.31.zip', 'ComplementaryReimagined_r5.9.3.zip', 'photon_v1.4.zip', 'BSL_v10.1.1.zip']


def write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    # Windows may briefly deny replacement while the JVM's UTF-8 reader has this exact file open.
    for attempt in range(40):
        try:
            os.replace(temporary, path)
            break
        except PermissionError as error:
            if getattr(error, 'winerror', None) not in (5, 32) or attempt == 39:
                raise
            time.sleep(.025)


def paths(name):
    if not name or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in name):
        raise ValueError('name must be a simple case identifier')
    case = ROOT / name
    return case, case / 'game', case / 'control'


def fixture(args):
    import zipfile
    cp = (PROJECT / 'build' / 'compile-classpath.txt').read_text(encoding='utf-8')
    classes = PROJECT / 'build' / 'fixture-classes'
    sources = [PROJECT / 'src/main/java/dev/yg/skyboxblock/client/Fixture262Client.java',
               *sorted((PROJECT / 'src/main/java/dev/yg/skyboxblock/mixin').glob('Fixture262*Mixin.java'))]
    subprocess.run([str(JAVA.with_name('javac.exe')), '-J-Duser.language=en', '-encoding', 'UTF-8',
                    '-proc:none', '-cp', cp, '-d', str(classes), *map(str, sources)], check=True)
    with zipfile.ZipFile(FIXTURE, 'w') as archive:
        archive.writestr('fabric.mod.json', json.dumps({
            'schemaVersion': 1, 'id': 'skybox_fixture', 'version': '1.0', 'name': 'Skybox integration fixture',
            'environment': 'client', 'entrypoints': {'client': ['dev.yg.skyboxblock.client.Fixture262Client']},
            'mixins': ['skyboxblock.fixture.mixins.json'],
            'depends': {'fabricloader': '>=0.20.1', 'minecraft': '26.2', 'fabric-api': '*'}}))
        archive.write(PROJECT / 'src/main/resources/skyboxblock.fixture.mixins.json', 'skyboxblock.fixture.mixins.json')
        for source in sorted(classes.rglob('Fixture262*.class')):
            archive.write(source, source.relative_to(classes).as_posix())
    print('fixture javac and independent control mod: PASS')


def prepare(args):
    case, game, control = paths(args.name)
    if game.exists():
        raise FileExistsError(f'Existing case preserved; use a new --name: {game}')
    (game / 'mods').mkdir(parents=True)
    control.mkdir(parents=True, exist_ok=True)
    copied = []
    for source in (SOURCE / 'mods').glob('*.jar'):
        lower = source.name.lower()
        keep = lower.startswith('fabric-api-') or (not getattr(args, 'plain', False) and
               ('iris-fabric-' in lower or 'sodium-fabric-' in lower))
        if args.dh and lower.startswith('distanthorizons-'):
            keep = True
        if keep:
            shutil.copy2(source, game / 'mods' / source.name)
            copied.append(source.name)
    if args.mod:
        mod = Path(args.mod).resolve(strict=True)
        shutil.copy2(mod, game / 'mods' / mod.name)
        # Final production jar also contains the opt-in fixture; the initial unmodified jar does not.
        import zipfile
        with zipfile.ZipFile(mod) as archive:
            has_fixture = 'dev/yg/skyboxblock/client/Fixture262Client.class' in archive.namelist()
        if not has_fixture:
            shutil.copy2(FIXTURE, game / 'mods' / FIXTURE.name)
    else:
        shutil.copy2(FIXTURE, game / 'mods' / FIXTURE.name)
    for folder in ['shaderpacks', 'config']:
        (game / folder).mkdir()
    for pack in PACKS:
        source = SOURCE / 'shaderpacks' / pack
        if source.exists():
            shutil.copy2(source, game / 'shaderpacks' / pack)
    (game / 'config' / 'iris.properties').write_text(
        'enableShaders=false\nshaderPack=iterationRP Alpha 0.8.31.zip\nallowUnknownShaders=false\n', encoding='utf-8')
    options = (SOURCE / 'options.txt').read_text(encoding='utf-8').splitlines()
    override = {'pauseOnLostFocus': 'false', 'enableVsync': 'false', 'maxFps': '60', 'lang': 'zh_cn',
                'renderDistance': '8', 'simulationDistance': '5', 'renderClouds': '"true"',
                'cloudRange': '128', 'fullscreen': 'false', 'bobView': 'true', 'soundCategory_master': '0.0',
                'inactivityFpsLimit': '"minimized"', 'mipmapLevels': '2', 'resourcePacks': '[]'}
    options = [line for line in options if line.split(':', 1)[0] not in override]
    options += [f'{key}:{value}' for key, value in override.items()]
    (game / 'options.txt').write_text('\n'.join(options) + '\n', encoding='utf-8')
    world = SOURCE / 'saves' / 'flat262'
    if not (world / 'level.dat').exists():
        raise FileNotFoundError(world / 'level.dat')
    # Copy generation metadata, not already modified chunks/old block entities. Each case gets a fresh flat world.
    (game / 'saves' / 'fixture').mkdir(parents=True)
    shutil.copy2(world / 'level.dat', game / 'saves' / 'fixture' / 'level.dat')
    # 26.2 externalizes dimension/world-generation settings under data/minecraft/*.dat.
    if (world / 'data').is_dir():
        shutil.copytree(world / 'data', game / 'saves' / 'fixture' / 'data')
    write_json(case / 'preparation.json', {'source': str(SOURCE), 'game': str(game), 'mod': args.mod,
                                         'modSha256': hashlib.sha256(Path(args.mod).read_bytes()).hexdigest() if args.mod else None,
                                         'plain': getattr(args, 'plain', False),
                                         'dh': args.dh, 'runtimeMods': copied, 'created': time.time()})
    print(game)


def launch(args):
    case, game, control = paths(args.name)
    spec = importlib.util.spec_from_file_location('launch262', PROJECT / 'tools' / 'launch-262.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    version = json.loads((SOURCE / (SOURCE.name + '.json')).read_text(encoding='utf-8'))
    cp = module.build_classpath(version)
    missing = [p for p in cp if not Path(p).is_file()]
    if missing:
        raise FileNotFoundError(missing)
    jvm = [str(JAVA), '-Xms1G', '-Xmx4G', '--sun-misc-unsafe-memory-access=allow',
           '--enable-native-access=ALL-UNNAMED', f'-Djava.library.path={module.NATIVES / "java"}',
           f'-Djna.tmpdir={module.NATIVES / "jna"}', f'-Dorg.lwjgl.system.SharedLibraryExtractPath={module.NATIVES / "lwjgl"}',
           f'-Dio.netty.native.workdir={module.NATIVES / "netty"}', f'-Dskyboxblock.fixture.control={control}',
           '-Dminecraft.launcher.brand=skybox-regression', '-Djava.awt.headless=false', '-cp', os.pathsep.join(cp),
           version['mainClass'], '--username', 'Regression', '--version', '26.2', '--gameDir', str(game),
           '--assetsDir', str(module.MC_ROOT / 'assets'), '--assetIndex', version['assetIndex']['id'],
           '--uuid', '00000000000000000000000000000000', '--accessToken', '0', '--userType', 'legacy',
           '--versionType', 'release', '--width', '960', '--height', '600', '--quickPlaySingleplayer', 'fixture']
    if args.jvm:
        jvm[1:1] = args.jvm
    # A relaunch must not replay the previous process's final stop command.
    write_json(control / 'request.json', {'id': 'boot-' + uuid.uuid4().hex, 'frames': 2})
    if (case / 'client.log').is_file():
        shutil.copy2(case / 'client.log', case / ('client-before-relaunch-' + str(int(time.time())) + '.log'))
    with (case / 'client.log').open('wb') as stream:
        proc = subprocess.Popen(jvm, stdout=stream, stderr=subprocess.STDOUT, cwd=game,
                                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
    write_json(case / 'process.json', {'pid': proc.pid, 'args': jvm, 'started': time.time()})
    print(f'pid={proc.pid} log={case / "client.log"}')


def process_alive(pid):
    if os.name != 'nt':
        return True
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.OpenProcess(0x1000, False, pid)
    if not handle:
        return False
    code = wintypes.DWORD()
    try:
        return bool(kernel.GetExitCodeProcess(handle, ctypes.byref(code))) and code.value == 259
    finally:
        kernel.CloseHandle(handle)


def call(args):
    case, game, control = paths(args.name)
    payload = json.loads(Path(args.file).read_text(encoding='utf-8')) if args.file else json.loads(args.json)
    payload.setdefault('id', uuid.uuid4().hex)
    write_json(control / 'request.json', payload)
    deadline = time.monotonic() + args.timeout
    result_path = control / 'result.json'
    process_path = case / 'process.json'
    pid = json.loads(process_path.read_text(encoding='utf-8'))['pid'] if process_path.exists() else None
    while time.monotonic() < deadline:
        if pid and not process_alive(pid):
            raise RuntimeError(f'Fixture JVM {pid} exited; inspect {case / "client.log"}')
        try:
            result = json.loads(result_path.read_text(encoding='utf-8'))
            if result.get('id') == payload['id']:
                label = payload.get('capture', payload['id']).replace('.png', '')
                write_json(control / (label + '.json'), result)
                print(json.dumps(result, ensure_ascii=False, indent=2))
                if not result['ok']:
                    raise RuntimeError(result.get('error'))
                return result
        except (FileNotFoundError, PermissionError, json.JSONDecodeError):
            pass
        time.sleep(.2)
    raise TimeoutError(f'Fixture response timed out: {case}; inspect client.log')


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('action', choices=['fixture', 'prepare', 'launch', 'call'])
    parser.add_argument('--name', required=True)
    parser.add_argument('--mod')
    parser.add_argument('--dh', action='store_true')
    parser.add_argument('--plain', action='store_true', help='Fabric API only; no Iris or Sodium')
    parser.add_argument('--jvm', action='append')
    parser.add_argument('--json', default='{}')
    parser.add_argument('--file')
    parser.add_argument('--timeout', type=int, default=180)
    args = parser.parse_args()
    globals()[args.action](args)


if __name__ == '__main__':
    main()
