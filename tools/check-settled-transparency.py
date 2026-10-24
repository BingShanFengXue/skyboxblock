#!/usr/bin/env python3
"""Audit complete settled53 evidence and pinned package; no image threshold waiver."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('settled_cli_protocol', Path(__file__).with_name('settled-transparency-protocol.py'))
S = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = S
spec.loader.exec_module(S)


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def check(root, expected_jar):
    root = Path(root).resolve(strict=True)
    if root.name == 'results':
        root = root.parent
    report = dict(kind='settled53-producer-protocol-and-package-not-image-acceptance',
                  directory=str(root), expectedJarSha256=expected_jar, passed=False, errors=[], inputs={},
                  imageThresholdsChanged=False, releaseAccepted=False)
    def observe(path):
        path = Path(path).resolve(strict=True)
        S.require(path.is_relative_to(root), 'input escapes actual fixture')
        report['inputs'][str(path)] = dict(sha256=digest(path), bytes=path.stat().st_size)
    try:
        S.require(isinstance(expected_jar, str) and len(expected_jar) == 64
                  and all(c in '0123456789abcdefABCDEF' for c in expected_jar), 'explicit SHA-256 pin required')
        expected_jar = expected_jar.lower()
        manifest = S.strict_json(root / 'fixture.json')
        observe(root / 'fixture.json')
        S.require(manifest.get('jarSha256') == expected_jar, 'declared package identity differs')
        installed = []
        for path in (root / 'mods').glob('*.jar'):
            observe(path)
            with zipfile.ZipFile(path) as archive:
                if 'fabric.mod.json' not in archive.namelist():
                    continue
                meta = json.loads(archive.read('fabric.mod.json'))
            if meta.get('id') == 'skyboxblock':
                installed.append(path)
        S.require(len(installed) == 1 and digest(installed[0]) == expected_jar,
                  'exactly one actual installed pinned skyboxblock required')
        protocol = S.inspect_fixture(root, manifest=manifest, observe=observe)
        S.require(protocol['settled'] is True, 'this gate requires the settled53 protocol, not legacy evidence')
        S.verify_warmup_images(root, protocol, observe=observe, all_no_sky=True)
        report['protocol'] = protocol
        report['passed'] = True
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        report['errors'].append(str(error))
    report['toolSha256'] = {p.name: digest(p) for p in (Path(__file__), Path(S.__file__))}
    report['limitations'] = ['Pinned producer observations and exact same-frame zero writes are checked.',
        'Recorded counters are not cryptographic proof of independent acquisition.',
        'No world restoration tolerance, marker reference validity, or release acceptance is waived.']
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('fixture', type=Path)
    parser.add_argument('--expected-jar-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = check(args.fixture, args.expected_jar_sha256)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n')
    print('PASS' if result['passed'] else 'FAIL', str(args.output), result['errors'])
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
