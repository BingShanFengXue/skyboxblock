#!/usr/bin/env python3
"""Validate existing frozen-JAR settled evidence; no GPU calls or image tolerances.

Legacy 51-slot image logic stays separate. Settled means exactly 2 named warmups
and 51 measurements, actual producer counters, declared water control, matching
capture order and successful runtime. This validates recorded evidence, not its
cryptographic authenticity or independent acquisition.
"""
from __future__ import annotations
import hashlib
import itertools
import json
import math
from pathlib import Path
import re

FRONTS = ('none', 'glass', 'water')
PROTECTIONS = ('none', 'pillar', 'hand', 'both')
WARMUPS = ('warmup-empty-front', 'warmup-empty-oblique')
MEASUREMENTS = tuple(f'trans-{slot}-{front}-{protection}-{state}'
    for slot, front, protection, state in itertools.product(
        ('open', 'sky'), FRONTS, PROTECTIONS, ('empty', 'marker'))) + tuple('trans-removed-' + x for x in FRONTS)
TIMELINE = WARMUPS + MEASUREMENTS
MILLISECONDS = 15000
ROOT_FRAMES = 300
POLICY = dict(minimumRealMilliseconds=MILLISECONDS, minimumRootFrames=ROOT_FRAMES,
              pose='actual target eye position stable')


class ProtocolError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ProtocolError('settled protocol: ' + message)


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def finite_vector(value, count):
    return isinstance(value, list) and len(value) == count and all(
        type(x) in (int, float) and math.isfinite(x) for x in value)


def strict_json(path):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'duplicate JSON key ' + key)
            result[key] = value
        return result
    return json.loads(Path(path).read_text(encoding='utf-8'), object_pairs_hook=pairs,
        parse_constant=lambda x: (_ for _ in ()).throw(ProtocolError('Nonfinite JSON: ' + x)))


def inspect_fixture(root, manifest=None, run=None, read_json=None, observe=None):
    """Return a protocol signature; raise on contradictory/incomplete settled data.

    Optional readers bind files into the caller's existing hash/source ledger.
    A legacy fixture never acquires invented new fields or a settled pass.
    """
    root = Path(root).resolve(strict=True)
    records = {}
    def read(path):
        path = Path(path).resolve(strict=True)
        require(path.is_relative_to(root), 'capture/metadata alias escapes fixture root')
        if observe is not None:
            observe(path)
        if read_json is not None:
            read_json(path)  # Keep the caller's hash ledger without inheriting a permissive JSON parser.
        data = strict_json(path)
        require(isinstance(data, dict), 'metadata must be an object: ' + path.name)
        return data
    m = read(root / 'fixture.json')
    require(manifest is None or manifest == m, 'caller manifest differs from actual file')
    require(m.get('settledTransparency') in (None, False, True)
            and (m.get('settledTransparency') is None or type(m['settledTransparency']) is bool),
            'settledTransparency must be a boolean when declared')
    result = dict(protocol='legacy-unsettled', settled=False,
                  signature=dict(protocol='legacy-unsettled'))
    paths = list((root / 'results').glob('trans-*.json'))
    if m.get('settledTransparency') is not True:
        for path in paths:
            data = read(path)
            require(data.get('fixtureSettlement') is None,
                    'settlement evidence without an explicit settled declaration: ' + path.name)
        return result
    r = read(root / 'run-result.json')
    require(run is None or run == r, 'caller runtime differs from actual file')
    require(m.get('suite') == 'transparency' and m.get('requestedScenes') == [],
            'settled requires complete transparency suite without an ad hoc subset')
    require(m.get('fixedHistoryWarmupScenes') == list(WARMUPS), 'exact two fixed history warmups required')
    require(type(m.get('nativeTransparencyControl')) is bool, 'explicit native control role required')
    native = m['nativeTransparencyControl']
    require(m.get('neverSkyWarmupScenes') == (list(WARMUPS) if native else []), 'neverSky warmup declaration mismatch')
    require(m.get('neverSkyMeasurementSceneCount') == (51 if native else None), 'neverSky measurement count mismatch')
    require(m.get('settlementPolicy') == POLICY
            and all(type(m['settlementPolicy'].get(k)) is int for k in ('minimumRealMilliseconds', 'minimumRootFrames')),
            'fixed 15000 ms / 300 root-frame policy required')
    for key in ('freezeShaderTime', 'freezeClouds', 'freezeFluidTicks', 'freezeWaterTextures'):
        require(m.get(key) is True, 'required independent animation/fluid control absent: ' + key)
    require(m.get('waterSpritePhase') == 'first-declared-resource-frame', 'canonical water resource frame required')
    require(m.get('fixtureInputIsolation') == 'owned-client-START_CLIENT_TICK-releaseAll-and-resetToggleKeys',
            'owned input guard declaration absent')
    require(type(r.get('exitCode')) is int and r['exitCode'] == 0 and r.get('finished') is True,
            'successful completed runtime required')
    require(type(r.get('scenes')) is int and r['scenes'] == len(TIMELINE), 'complete 53-scene run required')
    for key in ('errors', 'fatalErrors', 'renderThreadViolations'):
        require(r.get(key) == [], 'runtime errors or missing error evidence: ' + key)
    require(type(r.get('seconds')) in (int, float) and math.isfinite(r['seconds'])
            and r['seconds'] >= len(MEASUREMENTS) * MILLISECONDS / 1000,
            'runtime duration shorter than fixed measurement-only lower bound')
    actual = {p.stem for p in (root / 'results').glob('*.json')}
    require(actual == set(TIMELINE), 'exact 53 metadata identities required; missing=' +
            repr(sorted(set(TIMELINE) - actual)) + ' extra=' + repr(sorted(actual - set(TIMELINE))))
    log_path = root / 'client.log'
    if observe is not None:
        observe(log_path.resolve(strict=True))
    log = log_path.read_text(encoding='utf-8', errors='replace')
    captured = re.findall(r'\[skybox-regression\] CAPTURED ([a-z0-9-]+)\b', log)
    require(captured == list(TIMELINE), 'live capture log must preserve exact warmup/measurement order once each')
    require('[skybox-regression] FINISHED: 53 scenes' in log, 'actual 53-scene completion log absent')
    last_input = last_roots = last_sprite_ticks = -1
    minimum_ms = minimum_frames = None
    for name in TIMELINE:
        data = read(root / 'results' / (name + '.json'))
        require(data.get('scene') == name, 'actual scene name mismatch: ' + name)
        require(data.get('cameraType') == 'FIRST_PERSON' and finite_vector(data.get('camera'), 3),
                'finite actual first-person pose required: ' + name)
        require(finite_vector(data.get('inverseDisplayViewProjection'), 16), 'finite actual view matrix required: ' + name)
        require(integer(data.get('sceneRenderFrames'), 1), 'actual scene root frames absent: ' + name)
        require(data.get('initialGlError') == data.get('finalGlError') == 0
                and type(data.get('initialGlError')) is int and type(data.get('finalGlError')) is int,
                'actual GL errors/missing evidence: ' + name)
        require(data.get('resourceReloadPending') is False and data.get('itrpBridgeFailure') is None,
                'unstable resource/bridge state: ' + name)
        state_keys = ('irisMainBoundBefore', 'irisMainBoundAfter', 'irisOverridesBefore', 'irisOverridesAfter')
        require(all(key in data for key in state_keys) and 'irisFrameCounter' in data,
                'actual Iris/no-Iris state observation missing: ' + name)
        if m.get('shader'):
            require(all(type(data[key]) is bool for key in state_keys) and integer(data['irisFrameCounter']),
                    'enabled shader requires actual Iris flags and frame counter: ' + name)
        else:
            require(all(data[key] is None for key in state_keys) and data['irisFrameCounter'] is None,
                    'vanilla shader-free evidence contradicts actual Iris observations: ' + name)
        require(data[state_keys[0]] == data[state_keys[1]] and data[state_keys[2]] == data[state_keys[3]],
                'actual Iris logical state was not restored: ' + name)
        require(all(type(data.get(k)) is int and data[k] == 0 for k in
                    ('outsideRasterChangedPixels', 'outsideRasterDepthChangedPixels')),
                'private writes outside the active raster: ' + name)
        require(integer(data.get('fixtureInputGuardTicks'), 1) and data['fixtureInputGuardTicks'] > last_input,
                'actual owned input guard counter must advance: ' + name)
        last_input = data['fixtureInputGuardTicks']
        water_keys = ('frozenFixtureWaterSprites', 'observedFixtureWaterSprites',
                      'staticFixtureWaterSprites', 'suppressedFixtureWaterSpriteTicks')
        require(all(integer(data.get(k)) for k in water_keys), 'missing actual water-sprite counters: ' + name)
        frozen, observed, static, sprite_ticks = (data[k] for k in water_keys)
        require(observed >= 2 and 2 <= frozen + static <= observed,
                'both actual water sprite resources must be frozen/static: ' + name)
        require(sprite_ticks >= last_sprite_ticks and (frozen == 0 or sprite_ticks > 0),
                'animated water suppression counter missing or reversed: ' + name)
        last_sprite_ticks = sprite_ticks
        require(integer(data.get('neverSkyObservedRootFrames')), 'actual whole-neverSky root counter absent: ' + name)
        roots = data['neverSkyObservedRootFrames']
        if native:
            require(roots > last_roots and (name in WARMUPS or roots - last_roots >= ROOT_FRAMES),
                    'whole-neverSky root counter must advance through every stable measurement: ' + name)
            last_roots = roots
        else:
            require(roots == 0, 'candidate contradicts non-neverSky role: ' + name)
        no_sky = native or name in WARMUPS or not name.startswith('trans-sky-')
        if no_sky:
            require(data.get('blocks') == data.get('vertices') == data.get('depthChangedPixels') == 0
                    and data.get('itrpMaterialWrites') is False and data.get('itrpBackgroundInjected') is False
                    and data.get('itrpInjectionCount') == 0 and data.get('finalResolved') is False,
                    'sky-free capture performed private rendering: ' + name)
        if name in WARMUPS:
            require(data.get('transparency') is None and data.get('fixtureSettlement') is None,
                    'warmup must not masquerade as a settled transparency measurement: ' + name)
            require(data.get('sceneWaitTicks') == 300 and data['sceneRenderFrames'] >= ROOT_FRAMES,
                    'actual fixed warmup tick/root-frame evidence absent: ' + name)
            expected_x = .5 if name == WARMUPS[0] else 1.15
            require(all(abs(a-b) <= 2e-5 for a, b in zip(data['camera'], (expected_x, 141.62, -3.))),
                    'wrong fixed warmup camera: ' + name)
        else:
            evidence = data.get('fixtureSettlement')
            require(isinstance(evidence, dict), 'missing per-capture settlement evidence: ' + name)
            require(evidence.get('protocol') == 'fixed-real-time-and-root-render-frames'
                    and evidence.get('stablePoseRequired') is True
                    and type(evidence.get('requiredMilliseconds')) is int and evidence['requiredMilliseconds'] == MILLISECONDS
                    and type(evidence.get('requiredFrames')) is int and evidence['requiredFrames'] == ROOT_FRAMES,
                    'contradictory settlement requirement: ' + name)
            ms, frames = evidence.get('observedMilliseconds'), evidence.get('observedFrames')
            require(integer(ms, MILLISECONDS) and integer(frames, ROOT_FRAMES)
                    and frames <= data['sceneRenderFrames'], 'insufficient/inconsistent actual settlement: ' + name)
            minimum_ms = ms if minimum_ms is None else min(minimum_ms, ms)
            minimum_frames = frames if minimum_frames is None else min(minimum_frames, frames)
            t = data.get('transparency')
            require(isinstance(t, dict) and integer(t.get('fixtureGeometryVersion'), 3)
                    and t.get('fluidFreezeMode') == 'owned-scheduled-water-only',
                    'settled measurements require actual v3 physical/fluid evidence: ' + name)
            require(t.get('nativeRepeatControl') is native and t.get('requestedSky') is name.startswith('trans-sky-')
                    and t.get('skyPresent') is (not no_sky), 'measurement actual/requested sky role differs: ' + name)
            require(integer(t.get('serverSkyBlockCount')) and t['serverSkyBlockCount'] == (0 if no_sky else 88),
                    'actual server sky count differs from declared role: ' + name)
            for side in ('Server', 'Client'):
                geometry = t.get('actual' + side + 'Geometry')
                require(isinstance(geometry, dict)
                        and all(integer(geometry.get(k)) for k in ('sky', 'skyEntities', 'highSky', 'highSkyEntities'))
                        and geometry['sky'] == geometry['skyEntities'] == (0 if no_sky else 88)
                        and geometry['highSky'] == geometry['highSkyEntities'] == 0,
                        'actual physical sky lifecycle differs: ' + side + ' ' + name)
            require(all(integer(t.get(k)) for k in ('actualServerGameTime', 'actualClientGameTime')),
                    'actual live server/client clocks absent: ' + name)
            require(integer(data.get('suppressedFixtureWaterTicks'))
                    and (t.get('front') != 'water' or data['suppressedFixtureWaterTicks'] > 0),
                    'actual scheduled water control absent: ' + name)
        records[name] = data
    for key in ('actualServerGameTime', 'actualClientGameTime'):
        values = [records[name]['transparency'][key] for name in MEASUREMENTS]
        require(max(values) > min(values), 'live world clock did not advance: ' + key)
    if any(records[name]['frozenFixtureWaterSprites'] for name in TIMELINE):
        for sprite in ('water_still', 'water_flow'):
            require('owned water sprite first-frame freeze minecraft:block/' + sprite in log,
                    'actual first-resource-frame upload log absent: ' + sprite)
    signature = dict(protocol='settled53-v1', settlementPolicy=POLICY, freezeWaterTextures=True,
                     waterSpritePhase='first-declared-resource-frame', fixedHistoryWarmupScenes=list(WARMUPS))
    result.update(protocol='settled53-v1', settled=True, signature=signature,
                  metadataCount=len(records), measurementCount=len(MEASUREMENTS), warmupNames=list(WARMUPS),
                  minimumObservedMilliseconds=minimum_ms, minimumObservedFrames=minimum_frames,
                  neverSky=native, lastNeverSkyRootFrames=last_roots if native else 0,
                  limitation='Validates pinned producer observations and lifecycle counters, not independent acquisition authenticity.')
    return result


def verify_warmup_images(root, protocol, observe=None, all_no_sky=False):
    """Exact zero-write evidence, separate from the unchanged image tolerances."""
    if not protocol.get('settled'):
        return
    import numpy as np
    from PIL import Image
    root = Path(root).resolve(strict=True)
    names = WARMUPS
    if all_no_sky:
        names = TIMELINE if protocol['neverSky'] else tuple(n for n in TIMELINE if not n.startswith('trans-sky-'))
    for name in names:
        metadata = strict_json(root / 'results' / (name + '.json'))
        require(all(integer(metadata.get(k), 1) for k in ('width', 'height', 'opaqueWidth', 'opaqueHeight')),
                'actual image dimensions absent: ' + name)
        images = {}
        pairs = (('before', '-before'), ('opaque', '-opaque'), ('depth', '-depth-change'))
        if all_no_sky:
            pairs += (('final', ''), ('pre', '-pre-resolve'))
        for key, suffix in pairs:
            path = (root / 'results' / (name + suffix + '.png')).resolve(strict=True)
            require(path.is_relative_to(root), 'warmup image alias escapes fixture')
            if observe is not None:
                observe(path)
            with Image.open(path) as source:
                images[key] = np.asarray(source.convert('RGB')).copy()
        require(images['before'].shape == images['opaque'].shape == images['depth'].shape
                == (metadata['opaqueHeight'], metadata['opaqueWidth'], 3),
                'actual active image grids differ from metadata: ' + name)
        require(np.array_equal(images['before'], images['opaque']) and not np.any(images['depth']),
                'actual sky-free image contains private color/depth writes: ' + name)
        if all_no_sky:
            require(images['final'].shape == images['pre'].shape == (metadata['height'], metadata['width'], 3)
                    and np.array_equal(images['final'], images['pre']),
                    'actual sky-free final color changed after resolve: ' + name)
