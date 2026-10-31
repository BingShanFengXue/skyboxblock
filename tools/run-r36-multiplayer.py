#!/usr/bin/env python3
"""Owned Minecraft 1.21.1 dedicated + two REAL clients: server settings, clocks and TAB.

Default is prepare-only. --run explicitly starts only newly-created owned processes.
No Gradle, downloads, user saves, installed instance writes or global process killing.
A static launcher candidate is NEVER labelled runtime-verified until actual server/RCON readiness.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import struct
import subprocess
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ATTRIBUTES = ('sky_color', 'fog_color', 'fog_start_distance', 'fog_end_distance', 'sky_fog_end_distance',
              'cloud_color', 'cloud_height', 'cloud_fog_end_distance', 'sunrise_sunset_color', 'sun_angle',
              'moon_angle', 'star_angle', 'moon_phase', 'star_brightness', 'has_sky_occluder')
SKY = 'environment.visual/sky_color'
PREFIX = 'skyboxblock environment '


def actual_red(snapshot):
    visual = snapshot.get('visual', {})
    color = visual.get('skyColor', [])
    return visual.get('present') and visual.get('skyBlocks', 0) > 0 and len(color) == 3 and max(abs(color[i] - value) for i, value in enumerate((1, 0, 0))) < 1e-5


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def dump(path, value, *, new=False):
    path = Path(path)
    if path.is_symlink():
        raise RuntimeError('Refusing symbolic-link output: ' + str(path))
    data = json.dumps(value, indent=2, ensure_ascii=False) + '\n'
    if new:
        with path.open('x', encoding='utf-8') as stream:
            stream.write(data)
    else:
        temporary = path.with_name(path.name + '.r36-tmp')
        temporary.write_text(data, encoding='utf-8')
        os.replace(temporary, path)


def load_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def load_base():
    spec = importlib.util.spec_from_file_location('r36_multiplayer_r30_base', ROOT / 'tools/run-r30-probe.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.base, module.launch


def reserve_ports():
    sockets = []
    for _ in range(2):
        value = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        value.bind(('127.0.0.1', 0))
        sockets.append(value)
    ports = [value.getsockname()[1] for value in sockets]
    return ports, sockets


def extract_server(bundle, server_dir, client_cp):
    """Use the ACTUAL cached official bundle, never a guessed client/merged game jar."""
    destination = server_dir / 'official-runtime'
    destination.mkdir()
    extracted = []
    with zipfile.ZipFile(bundle) as archive:
        if 'net/minecraft/bundler/Main.class' not in archive.namelist():
            raise RuntimeError('Selected jar is not an official Minecraft server bundle')
        manifests = {}
        for kind in ('versions', 'libraries'):
            entries = []
            for line in archive.read('META-INF/' + kind + '.list').decode('utf-8').splitlines():
                digest, identifier, relative = line.split('\t')
                if not re.fullmatch('[0-9a-fA-F]{64}', digest):
                    raise RuntimeError('Unexpected official bundle hash format')
                if Path(relative).is_absolute() or Path(relative).drive or '..' in Path(relative).parts or '\\' in relative or ':' in relative:
                    raise RuntimeError('Unsafe official bundle entry')
                member = 'META-INF/' + kind + '/' + relative
                data = archive.read(member)
                if hashlib.sha256(data).hexdigest().lower() != digest.lower():
                    raise RuntimeError('Official bundle entry hash mismatch: ' + member)
                path = destination / kind / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                with path.open('xb') as stream:
                    stream.write(data)
                entries.append(dict(id=identifier, path=str(path), sha256=digest.lower()))
                extracted.append(path)
            manifests[kind] = entries
    games = [entry for entry in manifests['versions'] if entry['id'] == '1.21.1']
    if len(games) != 1:
        raise RuntimeError('Official server bundle does not identify exactly Minecraft 1.21.1')
    game = Path(games[0]['path'])
    with zipfile.ZipFile(game) as archive:
        if 'net/minecraft/server/Main.class' not in archive.namelist():
            raise RuntimeError('Extracted official server entrypoint absent')
        if json.loads(archive.read('version.json'))['id'] != '1.21.1':
            raise RuntimeError('Extracted actual game version mismatch')
    allowed = ('/net/fabricmc/', '/org/ow2/asm/', '/org/spongepowered/', '/io/github/llamalad7/')
    loader_cp = [Path(path) for path in client_cp[:-1] if any(part in str(path).replace('\\', '/') for part in allowed)]
    providers = []
    for path in loader_cp:
        with zipfile.ZipFile(path) as archive:
            if 'net/fabricmc/loader/impl/launch/knot/KnotServer.class' in archive.namelist():
                providers.append(str(path))
    if len(providers) != 1:
        raise RuntimeError('Expected exactly one cached Fabric KnotServer provider; found ' + repr(providers))
    classpath = [str(path) for path in loader_cp] + [entry['path'] for entry in manifests['libraries']] + [str(game)]
    if str(client_cp[-1]) in classpath:
        raise RuntimeError('Client Minecraft jar leaked into dedicated classpath')
    return classpath, game, dict(status='STATIC_VALIDATED_RUNTIME_UNVERIFIED', bundle=str(bundle),
                                bundleSha256=sha(bundle), entrypoint='net.fabricmc.loader.impl.launch.knot.KnotServer',
                                providers=providers, game=games[0], libraries=manifests['libraries'],
                                includesClientGameJar=False, externalDownloads=False)


class Rcon:
    def __init__(self, port, password):
        self.port, self.password = port, password

    @staticmethod
    def packet(identifier, kind, text):
        body = struct.pack('<ii', identifier, kind) + text.encode('utf-8') + b'\0\0'
        return struct.pack('<i', len(body)) + body

    @staticmethod
    def receive(connection):
        def exact(count):
            data = b''
            while len(data) < count:
                value = connection.recv(count - len(data))
                if not value:
                    raise EOFError('RCON closed before complete packet')
                data += value
            return data
        size, = struct.unpack('<i', exact(4))
        if not 10 <= size <= 4 * 1024 * 1024:
            raise RuntimeError('Invalid RCON frame length')
        data = exact(size)
        if data[-2:] != b'\0\0':
            raise RuntimeError('Invalid RCON packet terminator')
        identifier, kind = struct.unpack('<ii', data[:8])
        return identifier, kind, data[8:-2].decode('utf-8', errors='replace')

    def command(self, text):
        with socket.create_connection(('127.0.0.1', self.port), timeout=5) as connection:
            connection.settimeout(5)
            connection.sendall(self.packet(11, 3, self.password))
            for _ in range(4):
                identifier, kind, response = self.receive(connection)
                if identifier == -1:
                    raise RuntimeError('Owned RCON authentication rejected')
                if identifier == 11 and kind == 2:
                    break
            else:
                raise RuntimeError('Owned RCON auth response absent')
            connection.sendall(self.packet(12, 2, text))
            identifier, kind, response = self.receive(connection)
            if identifier != 12:
                raise RuntimeError('Unexpected owned RCON command response id')
            return response


class Fixture:
    def __init__(self, args):
        self.args = args
        self.path = ROOT / 'build/r36-multiplayer' / args.label
        self.path.mkdir(parents=True, exist_ok=False)
        self.owner = secrets.token_hex(16)
        self.password = secrets.token_hex(24)
        self.ports, self.reservations = reserve_ports()
        self.processes, self.sequences, self.rcon_evidence, self.pinned = [], {}, [], {}
        self.base, self.launch = load_base()
        self.jar, self.probe = args.jar.resolve(strict=True), args.probe.resolve(strict=True)
        with zipfile.ZipFile(self.jar) as archive:
            metadata = json.loads(archive.read('fabric.mod.json'))
            if metadata['id'] != 'skyboxblock' or metadata.get('environment', '*') not in ('*', 'server'):
                raise RuntimeError('Unexpected production mod id or client-only metadata')
        with zipfile.ZipFile(self.probe) as archive:
            if json.loads(archive.read('fabric.mod.json'))['id'] != 'skybox_r30_probe' or 'dev/yg/skyboxprobe/R36MultiplayerProbe.class' not in archive.namelist():
                raise RuntimeError('Probe jar has not compiled R36MultiplayerProbe')
        version = load_json(self.launch.VERSION_DIR / (self.launch.VERSION_NAME + '.json'))
        self.client_cp = self.launch.build_classpath(version)
        if any(not Path(path).is_file() for path in self.client_cp):
            raise RuntimeError('Pinned launcher has missing classpath files; no download attempted')
        self.server = self.path / 'server'
        self.server.mkdir()
        (self.server / 'runtime-tmp').mkdir()
        self.server_cp, self.server_game, launcher = extract_server(args.server_bundle.resolve(strict=True), self.server, self.client_cp)
        self.base.prepare_mods(self.server, 'vanilla', self.jar)
        self.base.make_world(self.server / 'fixture', terrain='flat')
        world_config = self.server / 'fixture/serverconfig'
        world_config.mkdir()
        (world_config / 'skyboxblock.properties').write_text('unknown.r36Owner=' + self.owner + '\nclouds=true\n'
            'vanillaCloudHeight=192\nvanillaCloudFadeStart=128\nvanillaCloudFadeEnd=384\n', encoding='utf-8')
        (self.server / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
        properties = {'server-ip': '127.0.0.1', 'server-port': str(self.ports[0]), 'enable-rcon': 'true',
            'rcon.port': str(self.ports[1]), 'rcon.password': self.password, 'online-mode': 'false',
            'enforce-secure-profile': 'false', 'enable-query': 'false', 'level-name': 'fixture', 'gamemode': 'spectator',
            'force-gamemode': 'true', 'spawn-protection': '0', 'view-distance': '4', 'simulation-distance': '5',
            'op-permission-level': '2', 'function-permission-level': '2', 'max-players': '2', 'white-list': 'false',
            'allow-flight': 'true', 'motd': 'Owned R36 multiplayer validation', 'sync-chunk-writes': 'true'}
        (self.server / 'server.properties').write_text(''.join(key + '=' + value + '\n' for key, value in properties.items()), encoding='utf-8')
        self.rcon = Rcon(self.ports[1], self.password)
        self.clients = {}
        for name, folder, local, jvm in [('SkyboxA', 'client-a', '#0000FF', None), ('SkyboxB', 'client-b', '#FFFF00', '#00FF00')]:
            client = self.path / folder
            client.mkdir()
            (client / 'native-cache').mkdir()
            self.base.prepare_mods(client, 'vanilla', self.jar)
            shutil.copy2(self.probe, client / 'mods' / self.probe.name)
            (client / 'config').mkdir()
            config = client / 'config/skyboxblock.properties'
            config.write_text('clouds=true\nenvironment.visual/sky_color=' + local + '\n', encoding='utf-8')
            (client / 'options.txt').write_text('version:3955\nenableVsync:false\nmaxFps:30\npauseOnLostFocus:false\n'
                'renderDistance:4\nsimulationDistance:5\nguiScale:2\nlang:en_us\nbobView:false\nrenderClouds:"false"\n'
                'resourcePacks:[]\nonboardAccessibility:false\ntutorialStep:none\n', encoding='utf-8')
            marker = dict(schemaVersion=1, owned=True, kind='r36-multiplayer-client', ownerToken=self.owner,
                          gameDir=str(client.resolve()), host='127.0.0.1', serverPort=self.ports[0], playerName=name)
            dump(client / 'r36-owned.json', marker, new=True)
            self.clients[name] = dict(path=client, jvm=jvm, localColor=local)
            self.sequences[name] = 0
            self.pinned[str(config)] = sha(config)
        for path in [self.jar, self.probe] + list((self.server / 'mods').glob('*.jar')) + [p for c in self.clients.values() for p in (c['path'] / 'mods').glob('*.jar')]:
            self.pinned[str(path)] = sha(path)
        self.summary = dict(status='PREPARED_NOT_EXECUTED', owned=True, ownerToken=self.owner, path=str(self.path.resolve()),
                            serverPort=self.ports[0], rconPort=self.ports[1], checks=[],
                            mainJar=str(self.jar), mainJarSha256=sha(self.jar), probeJar=str(self.probe), probeJarSha256=sha(self.probe),
                            serverLauncher=launcher, processes=[], untested=['all runtime steps'], releaseAccepted=False,
                            scope='fresh synthetic world and fresh owned clients only; no user instance writes')
        dump(self.path / 'r36-owned.json', dict(ownerToken=self.owner, owned=True, path=str(self.path.resolve()),
             serverPropertiesRedacted={k: v for k, v in properties.items() if k != 'rcon.password'}), new=True)
        self.save()
        self.write_commands()

    def save(self):
        self.summary['processes'] = [dict(role=p['role'], pid=p['process'].pid, executable=str(self.launch.JAVA),
            gameDir=str(p['cwd']), ownerToken=self.owner, log=str(p['log']), exitCode=p['process'].poll()) for p in self.processes]
        dump(self.path / 'summary.json', self.summary)
        dump(self.path / 'owned-processes.json', self.summary['processes'])

    def check(self, name, condition, **evidence):
        self.summary['checks'].append(dict(name=name, passed=bool(condition), **evidence))
        self.save()
        if not condition:
            raise AssertionError(name)

    def server_command(self):
        return [str(self.launch.JAVA), '-Xms512M', '-Xmx' + self.args.server_heap,
                '-Dfabric.gameJarPath=' + str(self.server_game), '-Dskyboxblock.regression=false',
                '-Djava.io.tmpdir=' + str(self.server / 'runtime-tmp'),
                '-cp', os.pathsep.join(self.server_cp), 'net.fabricmc.loader.impl.launch.knot.KnotServer', 'nogui']

    def client_command(self, name):
        info = self.clients[name]
        offline = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode('utf-8')).digest(), version=3)
        return [str(self.launch.JAVA), '-Xms512M', '-Xmx' + self.args.client_heap,
            '-Djava.library.path=' + str(self.launch.NATIVES),
            '-Dorg.lwjgl.system.SharedLibraryExtractPath=' + str(info['path'] / 'native-cache'),
            '-Djava.io.tmpdir=' + str(info['path'] / 'native-cache'),
            '-Djna.tmpdir=' + str(info['path'] / 'native-cache'), '-Dio.netty.native.workdir=' + str(info['path'] / 'native-cache'),
            '-Dskyboxblock.regression=false', '-Dskyboxblock.debug=false', '-Dskyboxblock.r30.enabled=false',
            '-Dskyboxblock.r36.multiplayerProbe=true', '-Dskyboxblock.r36.multiplayerProbe.owner=' + self.owner,
            *(['-Dskyboxblock.environment.visual/sky_color=' + info['jvm']] if info['jvm'] else []),
            '-cp', os.pathsep.join(self.client_cp), 'net.fabricmc.loader.impl.launch.knot.KnotClient',
            '--username', name, '--uuid', offline.hex, '--accessToken', '0', '--userType', 'legacy', '--version', '1.21.1',
            '--versionType', 'release', '--gameDir', str(info['path']), '--assetsDir', str(self.launch.MC_ROOT / 'assets'),
            '--assetIndex', '17', '--width', '960', '--height', '540', '--quickPlayMultiplayer', '127.0.0.1:' + str(self.ports[0])]

    def write_commands(self):
        dump(self.path / 'launch-commands.json', dict(server=self.server_command(),
             clients={name: self.client_command(name) for name in self.clients}), new=True)

    def spawn(self, role, command, cwd):
        log = self.path / (role + '.log')
        stream = log.open('xb')
        process = subprocess.Popen(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT)
        self.processes.append(dict(role=role, process=process, cwd=cwd.resolve(), log=log, stream=stream))
        self.save()
        return process

    def wait(self, description, probe, timeout=None):
        end = time.monotonic() + (timeout or self.args.step_timeout)
        last = None
        while time.monotonic() < end:
            try:
                value = probe()
                if value:
                    return value
            except (OSError, ValueError, EOFError, KeyError) as error:
                last = repr(error)
            for entry in self.processes:
                if entry['process'].poll() not in (None, 0) and not entry.get('expectedStop'):
                    raise RuntimeError('Owned process failed: ' + entry['role'] + '; see ' + str(entry['log']))
            time.sleep(.1)
        raise TimeoutError(description + '; last=' + str(last))

    def start_server(self, role):
        for reserved in self.reservations:
            reserved.close()
        self.reservations = []
        process = self.spawn(role, self.server_command(), self.server)
        def ready():
            if process.poll() is not None:
                raise RuntimeError('Dedicated exited before ready; see ' + role + '.log')
            text = (self.path / (role + '.log')).read_text(encoding='utf-8', errors='replace')
            return 'Done (' in text and self.rcon.command('list')
        response = self.wait('real dedicated log + authenticated RCON readiness', ready, self.args.startup_timeout)
        backend_time = self.rc(PREFIX + 'time get')
        backend_attribute = self.rc(PREFIX + 'get sky_color')
        self.check(role + '-real-common-backend-registered-on-dedicated', 'time =' in backend_time and 'sky_color' in backend_attribute, timeResponse=backend_time, attributeResponse=backend_attribute)
        self.summary['serverLauncher'].update(status='RUNTIME_DEDICATED_READY', readyLog=str(self.path / (role + '.log')), pid=process.pid)
        self.check(role + '-real-dedicated-ready', True, listResponse=response)
        return process

    def rc(self, command):
        response = self.rcon.command(command)
        self.rcon_evidence.append(dict(command=command, response=response, monotonic=time.monotonic()))
        dump(self.path / 'rcon-evidence.json', self.rcon_evidence)
        return response

    def state(self, name):
        value = load_json(self.clients[name]['path'] / 'r36-client-state.json')
        if value.get('ownerToken') != self.owner:
            raise RuntimeError('Client state owner mismatch')
        if value.get('status') == 'ERROR':
            raise RuntimeError('Java helper failure ' + name + ': ' + str(value))
        return value

    def control(self, name, operation, **fields):
        self.sequences[name] += 1
        sequence = self.sequences[name]
        directory = self.clients[name]['path']
        dump(directory / 'r36-control.json', dict(ownerToken=self.owner, sequence=sequence, operation=operation, **fields))
        evidence = directory / 'r36-evidence' / f'step-{sequence:06d}.json'
        value = self.wait(name + ' ' + operation, lambda: load_json(evidence) if evidence.exists() else False)
        if value.get('ownerToken') != self.owner or value.get('sequence') != sequence or value.get('status') == 'ERROR':
            raise RuntimeError('Client operation failed: ' + repr(value))
        return value

    def snap(self, name, screenshot=False):
        return self.control(name, 'snapshot', screenshot=screenshot)['snapshot']

    def until_snapshot(self, name, predicate, description):
        return self.wait(description, lambda: (lambda state: state if predicate(state) else False)(self.snap(name)))

    def both(self, predicate, description):
        a = self.until_snapshot('SkyboxA', predicate, description + ' A')
        b = self.until_snapshot('SkyboxB', predicate, description + ' B')
        return a, b

    def client_cmd(self, command):
        return self.control('SkyboxA', 'command', command=PREFIX + command)

    def connected(self, name):
        return self.until_snapshot(name, lambda s: s.get('connected') and s.get('enforced'), name + ' joined with enforced server state')

    def layout(self, name):
        self.rc('gamemode spectator ' + name)
        self.rc('tp ' + name + ' 0.5 140.5 0.5 0 0')

    def setup_world(self):
        self.rc('gamerule doDaylightCycle false')
        self.rc('gamerule doWeatherCycle false')
        self.rc('time set 6000')
        self.rc('weather clear')
        self.rc('forceload add -16 -16 16 16')
        response = self.rc('fill -8 132 8 8 148 8 skyboxblock:sky_block')
        self.check('owned-sky-wall-created', bool(re.search(r'filled|changed|placed|Successfully', response, re.I)), response=response)

    def exercise(self):
        self.summary.update(status='RUNNING_NOT_ACCEPTANCE', untested=['scenario not complete'])
        self.save()
        server = self.start_server('server-1')
        if self.args.server_only:
            self.summary.update(status='SERVER_LAUNCH_VERIFIED_MULTIPLAYER_UNTESTED', untested=['all two-client checks'])
            return
        self.setup_world()
        self.spawn('client-a', self.client_command('SkyboxA'), self.clients['SkyboxA']['path'])
        self.wait('A helper startup', lambda: self.state('SkyboxA'), self.args.startup_timeout)
        a = self.connected('SkyboxA')
        self.layout('SkyboxA')
        noon = self.until_snapshot('SkyboxA', lambda s: s.get('visual', {}).get('present') and s['visual'].get('skyBlocks', 0) > 0, 'actual default private sky frame visible')
        self.check('A-default-enforced-not-local-blue', a['enforced'] and a['environment'] == {} and a['revision'] == 0, snapshot=a, actualDefaultVisual=noon)
        self.rc('op SkyboxA')
        self.rc('deop SkyboxB')
        self.wait('A updated OP2 command tree', lambda: any(n['path'] == PREFIX.strip() + ' set' for n in self.snap('SkyboxA')['commandTree']))
        self.client_cmd('set sky_color #FF0000')
        a = self.until_snapshot('SkyboxA', lambda s: s['environment'].get(SKY) == '#FF0000' and s['revision'] > 0, 'A real player mutation broadcast')
        red_revision = a['revision']
        self.check('A-player-sendCommand-set-red', True, snapshot=a)
        self.spawn('client-b', self.client_command('SkyboxB'), self.clients['SkyboxB']['path'])
        self.wait('B helper startup', lambda: self.state('SkyboxB'), self.args.startup_timeout)
        b = self.connected('SkyboxB')
        self.layout('SkyboxB')
        a, b = self.both(lambda s: s['environment'].get(SKY) == '#FF0000' and s['revision'] == red_revision and actual_red(s), 'late join receives red same revision and actual private sky red')
        self.check('B-late-join-and-client-JVM-file-ignored', b['clientJvmSkyColor'] == '#00FF00' and a['environment'] == b['environment'], A=a, B=b)
        self.control('SkyboxB', 'command', command=PREFIX + 'get sky_color')
        before_messages = self.snap('SkyboxB')['messageCounter']
        self.control('SkyboxB', 'command', command=PREFIX + 'set sky_color #00FFFF')
        def denied(snapshot):
            messages = [m for m in snapshot['messages'] if m['id'] > before_messages]
            local_parse_rejected = any(m['text'] == 'Incorrect argument for command'
                                       for m in messages) and any('<--[HERE]' in m['text'] for m in messages)
            mutator_hidden = not any(n['path'] == PREFIX.strip() + ' set' for n in snapshot['commandTree'])
            return local_parse_rejected and mutator_hidden
        b = self.until_snapshot('SkyboxB', denied, 'synced command tree hides the OP-only mutator')
        time.sleep(1)
        a, b = self.both(lambda s: s['revision'] == red_revision and s['environment'].get(SKY) == '#FF0000', 'non-OP mutation leaves both unchanged')
        self.check('B-nonOP-player-mutation-denied-no-revision-change', True, A=a, B=b)
        self.client_cmd('reset sky_color')
        self.both(lambda s: SKY not in s['environment'], 'red override reset before clock test')
        self.client_cmd('time lock midnight')
        def locked(s):
            v = s.get('visual', {})
            return s['clockMode'] == 'locked' and s['clockBase'] == 18000 and v.get('present') and v.get('skyBlocks', 0) > 0 and abs(v.get('displayTicks', -1) - 18000) < 1e-6 and abs(v.get('phase', -1) - .5) < 1e-6
        a, b = self.both(locked, 'actual private display ticks and phase locked midnight')
        daytime = self.rc('time query daytime')
        self.check('private-midnight-two-clients-world-still-noon', a['clockAnchor'] == b['clockAnchor'] and a['nativeDayTime'] == b['nativeDayTime'] == 6000 and bool(re.search(r'\b6000\b', daytime)), A=a, B=b, worldQuery=daytime)
        day_luminance = sum(noon['visual']['skyColor'])
        night_luminances = [sum(s['visual']['skyColor']) for s in (a, b)]
        self.check('actual-private-midnight-colors-not-native-noon', day_luminance > .05 and all(value < day_luminance * .6 for value in night_luminances), noon=noon['visual'], A=a['visual'], B=b['visual'])
        self.control('SkyboxA', 'snapshot', screenshot=True)
        self.control('SkyboxB', 'snapshot', screenshot=True)
        self.client_cmd('time unlock')
        a0, b0 = self.both(lambda s: s['clockMode'] == 'running' and s.get('visual', {}).get('skyClockMode') == 'running', 'unlock both running')
        time.sleep(1.5)
        a1, b1 = self.snap('SkyboxA'), self.snap('SkyboxB')
        progressed = all((end['visual']['displayTicks'] - start['visual']['displayTicks']) % 24000 > 10 for start, end in ((a0, a1), (b0, b1)))
        aligned = a1['clockAnchor'] == b1['clockAnchor'] and a1['clockBase'] == b1['clockBase']
        for state in (a1, b1):
            visual = state['visual']
            expected = (state['clockBase'] + max(0, visual['gameTime'] - state['clockAnchor'])) % 24000
            difference = (visual['displayTicks'] - expected) % 24000
            fraction = (visual['displayTicks'] / 24000 - .25) % 1
            expected_phase = (fraction * 2 + (.5 - math.cos(fraction * math.pi) / 2)) / 3
            aligned = aligned and min(difference, 24000 - difference) < 1.1 and abs(visual['phase'] - expected_phase) < 1e-5
        self.check('shared-server-anchor-running-actual-render-advances', progressed and aligned, A0=a0, B0=b0, A1=a1, B1=b1)
        self.client_cmd('time reset')
        self.client_cmd('reset all')
        a, b = self.both(lambda s: s['clockMode'] == 'auto' and s['environment'] == {} and s.get('visual', {}).get('skyClockMode') == 'auto', 'reset clock and attributes broadcast and actual private clock automatic')
        self.check('reset-all-broadcast-two-clients', a['revision'] == b['revision'], A=a, B=b)
        for name in ('SkyboxA', 'SkyboxB'):
            for prefix, expected in [('', list(ATTRIBUTES)), ('visual/', ['visual/' + x for x in ATTRIBUTES]), ('minecraft:visual/', ['minecraft:visual/' + x for x in ATTRIBUTES])]:
                response = self.control(name, 'suggest', input=PREFIX + 'get ' + prefix)
                texts = [entry['text'] for entry in response['suggestions']]
                self.check(name + '-actual-client-TAB-' + (prefix or 'short'), len(texts) == len(set(texts)) == 15 and set(texts) == set(expected), response=response)
            snapshot = self.snap(name)
            nodes = snapshot['commandTree']
            kinds = {node.get('argumentKind') for node in nodes if 'argumentType' in node}
            expected_kinds = {'resource_location', 'brigadier_string'} if name == 'SkyboxA' else {'resource_location'}
            self.check(name + '-server-command-tree-received-and-builtins-deserialized', bool(nodes) and any(n['path'].endswith('get attribute') for n in nodes) and expected_kinds.issubset(kinds) and 'unclassified' not in kinds, tree=nodes)
            if name == 'SkyboxB':
                self.check('B-mutator-tree-hidden-or-real-server-rejected', not any(n['path'] == PREFIX.strip() + ' set' for n in nodes) or any(c['name'] == 'B-nonOP-player-mutation-denied-no-revision-change' and c['passed'] for c in self.summary['checks']), tree=nodes)
        query_counter = self.snap('SkyboxB')['messageCounter']
        self.control('SkyboxB', 'command', command=PREFIX + 'get sky_color')
        b = self.until_snapshot('SkyboxB', lambda s: any(m['id'] > query_counter and 'auto' in m['text'] for m in s['messages']), 'ordinary B new query feedback')
        self.check('ordinary-B-can-query', True, snapshot=b)
        self.client_cmd('set fog_color #445566')
        _, before_disconnect = self.both(lambda s: s['environment'].get('environment.visual/fog_color') == '#445566', 'nondefault cache sentinel before B disconnect')
        self.control('SkyboxB', 'disconnect')
        cleared = self.until_snapshot('SkyboxB', lambda s: not s['connected'] and not s['enforced'] and s['environment'] == {}, 'B disconnect clears nondefault authoritative cache in same JVM')
        self.control('SkyboxB', 'connect')
        self.connected('SkyboxB')
        self.layout('SkyboxB')
        b = self.until_snapshot('SkyboxB', lambda s: s['environment'].get('environment.visual/fog_color') == '#445566', 'B reconnect receives current server sentinel')
        self.check('B-same-JVM-disconnect-reconnect-no-cache-leak', b['enforced'] and b['revision'] == before_disconnect['revision'], before=before_disconnect, disconnected=cleared, reconnected=b)
        self.client_cmd('reset fog_color')
        self.both(lambda s: 'environment.visual/fog_color' not in s['environment'], 'remove cache sentinel before reload test')
        config = self.server / 'fixture/serverconfig/skyboxblock.properties'
        text = config.read_text(encoding='iso-8859-1')
        text = '\n'.join(line for line in text.splitlines() if not line.startswith(SKY + '=')) + '\n' + SKY + '=#112233\n'
        temporary = config.with_name('.r36-reload.properties')
        temporary.write_text(text, encoding='iso-8859-1')
        os.replace(temporary, config)
        before_reload = self.snap('SkyboxA')['revision']
        self.rc(PREFIX + 'reload')
        a, b = self.both(lambda s: s['environment'].get(SKY) == '#112233' and s['revision'] > before_reload, 'on-disk server reload broadcasts')
        self.check('server-file-reload-two-client-broadcast', a['revision'] == b['revision'] and 'unknown.r36Owner=' + self.owner in config.read_text(encoding='iso-8859-1'), A=a, B=b, configSha256=sha(config))
        if self.args.skip_restart:
            self.summary.update(status='INCOMPLETE_NOT_ACCEPTED', untested=['dedicated restart persisted state and client rejoin'])
            return
        self.client_cmd('time lock midnight')
        persist_a, persist_b = self.both(lambda s: s['clockMode'] == 'locked' and s['clockBase'] == 18000, 'persist clock as well as reloaded attribute before restart')
        persisted_anchor = persist_a['clockAnchor']
        persisted_hash = sha(config)
        self.check('clock-and-attribute-checkpoint-before-restart', persist_a['clockAnchor'] == persist_b['clockAnchor'], A=persist_a, B=persist_b, configSha256=persisted_hash)
        self.stop_server(server)
        self.both(lambda s: not s['connected'] and not s['enforced'] and s['environment'] == {}, 'server shutdown clears both caches')
        self.start_server('server-2')
        for name in ('SkyboxA', 'SkyboxB'):
            self.control(name, 'connect')
            self.connected(name)
            self.layout(name)
        a, b = self.both(lambda s: s['environment'].get(SKY) == '#112233' and s['revision'] == 0 and s['clockMode'] == 'locked' and s['clockBase'] == 18000 and s['clockAnchor'] == persisted_anchor and s.get('visual', {}).get('displayTicks') == 18000, 'restarted dedicated reloads persisted attribute and clock into fresh connections')
        self.check('dedicated-restart-persistence-and-both-rejoin', sha(config) == persisted_hash and a['nativeDayTime'] == b['nativeDayTime'] == 6000, A=a, B=b, configSha256=sha(config), worldQuery=self.rc('time query daytime'))
        self.summary['untested'] = []

    def stop_server(self, process):
        for entry in self.processes:
            if entry['process'] is process:
                entry['expectedStop'] = True
        try:
            self.rc('stop')
        except (OSError, EOFError):
            pass
        process.wait(timeout=60)

    def shutdown_owned(self):
        for name in self.clients:
            try:
                if any(p['role'] == ('client-a' if name == 'SkyboxA' else 'client-b') and p['process'].poll() is None for p in self.processes):
                    self.control(name, 'stop')
            except Exception as error:
                self.summary.setdefault('shutdownNotes', []).append(name + ': ' + repr(error))
        servers = [p for p in self.processes if p['role'].startswith('server') and p['process'].poll() is None]
        if servers:
            try:
                self.stop_server(servers[-1]['process'])
            except Exception as error:
                self.summary.setdefault('shutdownNotes', []).append('server: ' + repr(error))
        for entry in self.processes:
            process = entry['process']
            if process.poll() is None:
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    self.summary.setdefault('forcedOwnedStops', []).append(dict(role=entry['role'], pid=process.pid))
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
            entry['stream'].close()
        for reservation in self.reservations:
            reservation.close()
        self.reservations = []
        self.save()

    def audit_logs(self):
        audits = {}
        error_header = re.compile(r'/(?:ERROR|FATAL)\]|\b(?:ERROR|FATAL)\s*\]')
        header = re.compile(r'^\[\d{2}:\d{2}:\d{2}\]')
        allowed_key = re.compile(r'Failed to request yggdrasil public key|Failed to retrieve profile key pair', re.I)
        fatal = re.compile(r'NoClassDefFoundError|Cannot load class .*environment type SERVER|Encountered an unexpected exception|Exception in thread|Failed to (?:synchronize|deserialize).*registr|Unknown argument type|EXCEPTION_ACCESS_VIOLATION|OutOfMemoryError', re.I)
        for entry in self.processes:
            lines = entry['log'].read_text(encoding='utf-8', errors='replace').splitlines()
            allowed, rejected, outside = [], [], []
            i = 0
            while i < len(lines):
                if error_header.search(lines[i]):
                    block = [lines[i]]
                    i += 1
                    while i < len(lines) and not header.match(lines[i]) and not error_header.search(lines[i]):
                        block.append(lines[i]); i += 1
                    joined = '\n'.join(block)
                    (allowed if allowed_key.search(block[0]) and not fatal.search(joined) else rejected).append(joined)
                else:
                    outside.append(lines[i]); i += 1
            extra_fatal = [line for line in outside if fatal.search(line)]
            crash_reports = [str(p) for p in entry['cwd'].glob('crash-reports/*.txt')]
            audits[entry['role']] = dict(log=str(entry['log']), allowedOfflineKeyFetchErrors=allowed,
                otherErrors=rejected, fatalOutsideAllowed=extra_fatal, crashReports=crash_reports,
                exitCode=entry['process'].returncode, ownerToken=self.owner)
        dump(self.path / 'log-audit.json', audits)
        return audits

    def run(self):
        failed = None
        try:
            self.exercise()
        except Exception as error:
            failed = repr(error)
            self.summary.update(status='FAILED', failure=failed)
        finally:
            self.shutdown_owned()
        audits = self.audit_logs()
        logs_ok = all(not a['otherErrors'] and not a['fatalOutsideAllowed'] and not a['crashReports'] and a['exitCode'] == 0 for a in audits.values())
        stable = {path: sha(path) == digest for path, digest in self.pinned.items()}
        self.summary['logAudit'] = audits
        self.summary['inputBytesUnchanged'] = stable
        self.summary['checks'].append(dict(name='all-owned-logs-no-unexpected-errors', passed=logs_ok))
        self.summary['checks'].append(dict(name='all-production-probe-and-local-config-inputs-unchanged', passed=all(stable.values())))
        if failed or not logs_ok or not all(stable.values()) or self.summary.get('forcedOwnedStops'):
            self.summary['status'] = 'FAILED'
        elif self.args.server_only:
            self.summary['status'] = 'SERVER_LAUNCH_VERIFIED_MULTIPLAYER_UNTESTED'
        elif not self.summary['untested'] and all(c['passed'] for c in self.summary['checks']):
            self.summary['status'] = 'PASS'
        else:
            self.summary['status'] = 'INCOMPLETE_NOT_ACCEPTED'
        self.summary['releaseAccepted'] = False
        self.save()
        print(json.dumps(dict(path=str(self.path), status=self.summary['status'], checks=len(self.summary['checks']), failure=failed), indent=2))
        return 0 if self.summary['status'] in ('PASS', 'SERVER_LAUNCH_VERIFIED_MULTIPLAYER_UNTESTED') else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label', required=True, help='New owned directory label; never reuse an existing label')
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/skyboxblock-1.0.4-test.2-r36.jar')
    parser.add_argument('--probe', type=Path, default=ROOT / 'build/libs/skybox-validation-probe-r36.jar')
    parser.add_argument('--server-bundle', type=Path, default=ROOT / '.gradle-home/caches/fabric-loom/1.21.1/minecraft-server.jar')
    parser.add_argument('--run', action='store_true', help='Explicitly start only the new owned dedicated/client processes')
    parser.add_argument('--server-only', action='store_true', help='Verify actual dedicated launcher only; never labels multiplayer PASS')
    parser.add_argument('--skip-restart', action='store_true', help='Mark restart persistence UNTESTED and full suite INCOMPLETE')
    parser.add_argument('--server-heap', default='2G')
    parser.add_argument('--client-heap', default='2G')
    parser.add_argument('--startup-timeout', type=float, default=240)
    parser.add_argument('--step-timeout', type=float, default=45)
    args = parser.parse_args(argv)
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{2,63}', args.label):
        parser.error('Unique lower-case label (3..64 chars) required')
    if not all(re.fullmatch(r'[1-9][0-9]*[MG]', value, re.I) for value in (args.server_heap, args.client_heap)):
        parser.error('Explicit heap such as 2G required')
    if not 10 <= args.startup_timeout <= 900 or not 5 <= args.step_timeout <= 120:
        parser.error('Bounded startup/step timeouts required')
    fixture = Fixture(args)
    if args.run:
        return fixture.run()
    for reserved in fixture.reservations:
        reserved.close()
    fixture.reservations = []
    print(json.dumps(dict(path=str(fixture.path), status='PREPARED_NOT_EXECUTED', serverLauncher=fixture.summary['serverLauncher']['status']), indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())