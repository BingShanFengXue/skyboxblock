#!/usr/bin/env python3
"""Independent empirical controlled-response repeat diagnostic, NOT a final audit.

--ref-a/--ref-b: two independent NeverSky51 or fully validated settled53 timelines. --candidate: trans-open
NoSky holdout and trans-sky target. All thresholds/bands are frozen to the old
oracle; no threshold switches, resizing, SkyOn-fitted ROI, game or build calls.
--references-only diagnoses A/B without accepting a candidate. Reports are new
files only. A perfectly repeated marker-slot nuisance remains unidentifiable:
repeatability plus physical controls is NOT unique proof of marker causality.
"""
from __future__ import annotations
import argparse
from collections import defaultdict
import hashlib
import importlib.util
import itertools
import json
from pathlib import Path
import re
import struct
import sys
import tomllib
import zipfile
import numpy as np

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
BANDS = ((4, 16), (8, 32), (16, 64))
FROZEN = dict(min_pixels=64, margin_active=3, max_hand_fraction=.20, marker_shift_active=6,
              marker_reference_rms=3., marker_correlation=.35, marker_gain=.08, marker_residual_rms=1.5,
              shape_correlation=.75, shape_gain=.15, shape_residual_rms=2.,
              shape_reference_rms=3., shape_geometry_correlation=.60, shape_min_bands=2)
EVIDENCE_TYPE = "empirical-controlled-response-repeat-v1"


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / file)
    result = importlib.util.module_from_spec(spec); sys.modules[name] = result; spec.loader.exec_module(result)
    return result


O = module("marker_repeat_geometry_oracle", "check-transparency-regression.py")
P = module("marker_repeat_settings_oracle", "check-portal-regression.py")
if any(O.DEFAULTS.get(key) != value for key, value in FROZEN.items()):
    raise RuntimeError("Upstream threshold contract changed; review this independent diagnostic")
LIMITS = dict(O.DEFAULTS)


class NotVerified(ValueError): pass


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""): digest.update(block)
    return digest.hexdigest()


def hash_array(array): return hashlib.sha256(np.ascontiguousarray(array).tobytes()).hexdigest()


def required(data, keys, context):
    if not isinstance(data, dict): raise NotVerified("Missing metadata object: " + context)
    missing = sorted(set(keys) - data.keys())
    if missing: raise NotVerified("Missing metadata " + context + ": " + ", ".join(missing))


def finite(value, shape, context):
    array = np.asarray(value, dtype=float)
    if array.shape != shape or not np.all(np.isfinite(array)):
        raise NotVerified("Nonfinite/malformed metadata: " + context)
    return array


def physical_evidence(meta, native, name):
    """Versioned producer evidence; v1 never acquires invented newer fields.

    Use the original oracle's exact fixed-recipe counts, not pixel inference or
    a second hand-maintained geometry recipe. Live clocks are separate from the
    intentionally phase-fixed displaySky metadata and need not equal each other.
    """
    t = meta["transparency"]; version = t.get("fixtureGeometryVersion", 1)
    if type(version) is not int or version < 1:
        raise NotVerified("Invalid fixtureGeometryVersion: " + name)
    record = dict(fixtureGeometryVersion=version)
    if version < 2: return record
    expected = O.expected_fixture_geometry(t)
    record.update(markerPattern=t["markerPattern"], expectedGeometry=expected)
    for side in ("Server", "Client"):
        key = "actual" + side + "Geometry"; observed = t.get(key)
        if (not isinstance(observed, dict) or observed != expected
                or any(type(value) is not int for value in observed.values())):
            raise NotVerified("Exact physical " + key + " mismatch: " + name
                              + " expected=" + json.dumps(expected, sort_keys=True)
                              + " actual=" + json.dumps(observed, sort_keys=True))
        record[key] = dict(observed)
    if version < 3: return record
    if t.get("fluidFreezeMode") != "owned-scheduled-water-only":
        raise NotVerified("v3 requires owned-scheduled-water-only, not global freeze: " + name)
    record["fluidFreezeMode"] = t["fluidFreezeMode"]
    for key in ("actualServerGameTime", "actualClientGameTime"):
        if type(t.get(key)) is not int or t[key] < 0:
            raise NotVerified("Missing/invalid v3 live clock " + key + ": " + name)
        record[key] = t[key]
    count = meta.get("suppressedFixtureWaterTicks")
    if type(count) is not int or count < 0 or (t["front"] == "water" and count <= 0):
        raise NotVerified("Missing/invalid actual v3 suppressedFixtureWaterTicks control: " + name)
    record["suppressedFixtureWaterTicks"] = count
    if native:
        count = meta.get("neverSkyObservedRootFrames")
        if type(count) is not int or count <= 0:
            raise NotVerified("Missing/invalid v3 whole-NoSky root counter neverSkyObservedRootFrames: " + name)
        record["neverSkyObservedRootFrames"] = count
    return record


def advancing_clocks(records, context):
    records = list(records)
    if len({record["fixtureGeometryVersion"] for record in records}) > 1:
        raise NotVerified("Mixed fixture geometry versions within one observed timeline: " + context)
    current = [record for record in records if record["fixtureGeometryVersion"] >= 3]
    if not current: return
    for key in ("actualServerGameTime", "actualClientGameTime"):
        times = [record[key] for record in current]
        if len(times) < 2 or max(times) <= min(times):
            raise NotVerified("v3 live world clock does not advance (global freeze): " + key + " " + context)
    roots = [record["neverSkyObservedRootFrames"] for record in current if "neverSkyObservedRootFrames" in record]
    if roots and (len(roots) != len(current) or len(roots) < 2 or max(roots) <= min(roots)):
        raise NotVerified("v3 whole-NoSky root counter does not advance across reference scenes: " + context)


class Sources:
    """Identity checks apply to fixture capture files, not intentionally equal jars/masks.

    Resolve scene paths inside their OWN real fixture. Reject actual cross-root
    file aliases/hardlinks. Equal final pixels alone are a source-review observation:
    independent deterministic frozen captures may legitimately be identical.
    """
    def __init__(self): self.identities, self.captures, self.observations = {}, {}, []

    def observe(self, path, root, final_pixels=None):
        stat = path.stat(); identity = (stat.st_dev, stat.st_ino)
        prior = self.identities.get(identity)
        if prior is not None and prior[0] != root:
            raise NotVerified("Cross-fixture same-source file reuse: " + str(path) + " / " + str(prior[1]))
        self.identities[identity] = (root, path)
        if final_pixels is not None:
            key = (final_pixels.shape, hash_array(final_pixels))
            prior = self.captures.get(key)
            if prior is not None and prior[0] != root:
                observation = dict(type="duplicate-content-observation/source-review", first=str(prior[1]), second=str(path),
                                   pixelSha256=key[1], meaning="Equal pixels alone do not establish same-source reuse; deterministic repeats are admissible.")
                if observation not in self.observations: self.observations.append(observation)
            self.captures[key] = (root, path)


class Fixture:
    def __init__(self, path, expected_jar, native, project, inputs, sources):
        path = Path(path).resolve(strict=True)
        self.root = path.parent if path.name == "results" else path
        self.results = (self.root / "results").resolve(strict=True)
        if self.results.parent != self.root or self.results.name != "results":
            raise NotVerified("Same-source results alias escapes own real fixture")
        self.project = Path(project).resolve(strict=True)
        if not self.root.is_relative_to(self.project): raise NotVerified("Fixture escapes declared project")
        self.inputs, self.sources, self.native, self.cache = inputs, sources, native, {}
        self.physical = {}
        self.manifest = self.read_json(self.root / "fixture.json")
        run = self.read_json(self.root / "run-result.json")
        required(run, ("exitCode", "finished", "fatalErrors", "scenes"), "run-result")
        if run["exitCode"] != 0 or run["finished"] is not True or run["fatalErrors"]:
            raise NotVerified("Fixture run is incomplete/fatal: " + str(self.root))
        m = self.manifest
        required(m, ("jarSha256", "jar", "nativeTransparencyControl", "freezeShaderTime", "freezeClouds",
                     "captureJitterBits", "profile", "shader", "shaderOptions", "mods"), "fixture")
        if m["jarSha256"].lower() != expected_jar: raise NotVerified("Declared jar hash differs from explicit pin")
        jar = Path(m["jar"]); jar = jar if jar.is_absolute() else self.project / jar
        if self.file_hash(jar, local=False) != expected_jar: raise NotVerified("Accepted frozen jar changed")
        if m["nativeTransparencyControl"] is not native: raise NotVerified("Wrong nativeTransparencyControl role")
        if m["freezeShaderTime"] is not True or m["freezeClouds"] is not True:
            raise NotVerified("Required frozen animation policy absent")
        bits = m["captureJitterBits"]
        if not isinstance(bits, str) or not re.fullmatch(r"[0-9a-fA-F]{8}:[0-9a-fA-F]{8}", bits):
            raise NotVerified("Missing/malformed captureJitterBits")
        self.jitter = [struct.unpack("!f", bytes.fromhex(x))[0] for x in bits.split(":")]
        finite(self.jitter, (2,), "fixture jitter bits")
        log = self.root / "client.log"; self.file_hash(log)
        if m["shader"] and "fixture-only Iris frameTimeCounter=0 active" not in log.read_text(encoding="utf-8", errors="replace"):
            raise NotVerified("No live owned shader-time activation evidence")
        mods = {}
        for installed in sorted((self.root / "mods").glob("*.jar")):
            digest = self.file_hash(installed)
            with zipfile.ZipFile(installed) as archive:
                if "fabric.mod.json" not in archive.namelist(): raise NotVerified("Unidentified installed mod: " + installed.name)
                info = json.loads(archive.read("fabric.mod.json"))
            if info["id"] in mods: raise NotVerified("Duplicate installed mod identity")
            mods[info["id"]] = dict(sha256=digest, version=info["version"])
        if mods.get("skyboxblock", {}).get("sha256") != expected_jar:
            raise NotVerified("Installed main jar is not pinned frozen jar")
        declared_mods = {x["id"]: x["version"] for x in m["mods"]}
        if len(declared_mods) != len(m["mods"]) or set(declared_mods) - mods.keys():
            raise NotVerified("Declared installed mod inventory differs")
        if set(mods) != set(declared_mods) | {"skyboxblock"}:
            raise NotVerified("Undeclared installed mod identity")
        if any(mods[key]["version"] != version for key, version in declared_mods.items()):
            raise NotVerified("Actual installed mod version differs")
        shader = m["shader"]
        properties_path = self.root / "config/iris.properties"; self.file_hash(properties_path)
        props = P.properties(properties_path.read_text(encoding="utf-8"))
        if shader:
            pack = self.child(self.root / "shaderpacks", shader); pack_hash = self.file_hash(pack)
            text_path = pack.with_name(pack.name + ".txt")
            actual_text = text_path.read_text(encoding="utf-8") if text_path.exists() else None
            if text_path.exists(): self.file_hash(text_path)
            declared = "\n".join(str(k) + "=" + str(v) for k, v in m["shaderOptions"].items())
            settings = P.settings_evidence(dict(shader=shader, shaderSha256=pack_hash, settings=declared), actual_text, pack_hash)
            if not settings["matched"]: raise NotVerified("Actual semantic shader settings mismatch")
            if props.get("enableShaders", "").lower() != "true" or props.get("shaderPack") != shader:
                raise NotVerified("Actual Iris pack selection differs")
        else:
            if (m["profile"] != "vanilla" or "iris" in mods or m["shaderOptions"] != {}
                    or props.get("enableShaders", "").lower() != "false" or props.get("shaderPack")):
                raise NotVerified("No-shader repeat requires actual vanilla profile, absent Iris and disabled pack")
            pack_hash = None
            settings = dict(matched=True, actualEffective={}, shaderDisabled=True,
                            scope="Actual no-Iris vanilla control; no shader-time activation claim")
        dh = dict(installed="distanthorizons" in mods, configPresent=False)
        policy = m.get("distantGenerationPolicy", {})
        if not isinstance(policy, dict): raise NotVerified("Malformed distantGenerationPolicy")
        requested = policy.get("requested")
        if requested not in (None, "instance", "disabled"): raise NotVerified("Unreviewed distantGenerationPolicy request")
        config = self.root / "config/DistantHorizons.toml"
        self.distant_policy = dict(requested=requested, actual=dh, dynamicLodAcceptance=False)
        if dh["installed"] and config.exists():
            digest = self.file_hash(config)
            try:
                parsed = tomllib.loads(config.read_text(encoding="utf-8"))
                plan = parsed["common"]["worldGenerator"]["generatorPlan"]
                renderer = parsed["client"]["advanced"]["debugging"]["rendererMode"]
                if any(not isinstance(value, str) or not value.strip() for value in (plan, renderer)):
                    raise ValueError("Missing semantic DH string")
            except (KeyError, TypeError, ValueError) as error:
                raise NotVerified("Actual DH TOML lacks valid generatorPlan/rendererMode semantics: " + str(error)) from error
            dh.update(configPresent=True, generatorPlan=plan, rendererMode=renderer)
            self.distant_policy["actualConfigSha256"] = digest
        if requested == "disabled":
            if (not dh.get("installed") or dh.get("generatorPlan") != "DISABLED" or dh.get("rendererMode") != "DEFAULT"
                    or policy.get("actualGeneratorPlan") != "DISABLED" or policy.get("rendererModeUnchanged") != "DEFAULT"
                    or not isinstance(policy.get("limitation"), str) or not policy["limitation"].strip()):
                raise NotVerified("DH requested=disabled requires actual DISABLED generation + unchanged DEFAULT renderer and explicit limitation")
            self.distant_policy["limitation"] = policy["limitation"]
        if dh.get("generatorPlan") == "DISABLED":
            self.distant_policy["warning"] = "Controlled near-world only; actual dynamic distant-generation/LOD compatibility is not established."
        def observe_protocol(path):
            self.file_hash(path)
            if path.parent == self.results:
                self.sources.observe(path, self.root)
        self.protocol = O.S.inspect_fixture(self.root, m, run, self.read_json, observe_protocol)
        O.S.verify_warmup_images(self.root, self.protocol, observe_protocol, all_no_sky=True)
        self.signature = dict(jar=expected_jar, mods=mods, shader=shader, shaderHash=pack_hash,
                              effectiveSettings=settings["actualEffective"], profile=m["profile"],
                              freezeShaderTime=True, freezeClouds=True, captureJitterBits=bits.lower(), distantGeneration=dh,
                              transparencyProtocol=self.protocol["signature"])
        self.settings = settings
        self.timeline_names = {p.stem for p in self.results.glob("trans-*.json")}
        if native:
            expected = {f"trans-{slot}-{front}-{protection}-{state}" for slot, front, protection, state in
                        itertools.product(("open", "sky"), O.FRONTS, O.PROTECTIONS, ("empty", "marker"))}
            expected |= {"trans-removed-" + front for front in O.FRONTS}
            expected_capture_count = 53 if self.protocol["settled"] else 51
            if self.timeline_names != expected or run["scenes"] != expected_capture_count:
                raise NotVerified("Never-sky reference must preserve complete 51-slot timeline plus declared settled warmups")
            # All 51 NoSky states need actual clean depth/before/opaque evidence,
            # not just clean metadata on whichever reference slot is later selected.
            for name in sorted(expected): self.scene(name, True, name.startswith("trans-sky-"), cache=False)
            advancing_clocks(self.physical.values(), "NeverSky complete 51-scene reference " + str(self.root))

    def child(self, directory, name):
        path = (directory / name).resolve(strict=True)
        if not path.is_relative_to(directory.resolve()): raise NotVerified("Input filename escapes fixture directory")
        return path

    def file_hash(self, path, local=True):
        path = Path(path).resolve(strict=True)
        if not path.is_relative_to(self.project): raise NotVerified("Input escapes project")
        if local and not path.is_relative_to(self.root): raise NotVerified("Same-source input escapes own fixture")
        stat = path.stat(); previous = self.inputs.get(str(path))
        if previous is not None and previous["size"] == stat.st_size and previous["mtimeNs"] == stat.st_mtime_ns:
            return previous["sha256"]
        value = sha(path)
        if previous is not None and previous["sha256"] != value: raise NotVerified("Input changed during proof: " + str(path))
        self.inputs[str(path)] = dict(size=stat.st_size, mtimeNs=stat.st_mtime_ns, sha256=value)
        return value

    def read_json(self, path):
        self.file_hash(path)
        return json.loads(Path(path).read_text(encoding="utf-8"), parse_constant=lambda x: (_ for _ in ()).throw(NotVerified("Nonfinite JSON: " + x)))

    def scene(self, name, no_sky, requested, cache=True):
        if name in self.cache: return self.cache[name]
        path = self.results / (name + ".json"); data = self.read_json(path)
        self.sources.observe(path.resolve(), self.root)
        required(data, ("scene", "transparency", "width", "height", "opaqueWidth", "opaqueHeight", "camera",
                       "inverseDisplayViewProjection", "inverseViewProjection", "rasterScale", "rasterJitter",
                       "targetViewport", "activeViewport", "displaySky", "dimension", "cameraType", "heldItem", "offhandItem",
                       "depthChangedPixels", "blocks", "vertices", "itrpMaterialWrites", "itrpBackgroundInjected",
                       "itrpInjectionCount", "itrpBridgeFailure", "resourceReloadPending", "initialGlError", "finalGlError"), name)
        if data["scene"] != name: raise NotVerified("Scene identity mismatch: " + name)
        t = data["transparency"]
        required(t, ("front", "protection", "behind", "skyPresent", "requestedSky", "nativeRepeatControl", "role",
                     "serverSkyBlockCount", "skyBounds", "behindBounds", "markerBoxes", "markerMaterial", "frontBounds", "pillarBounds"), name)
        if t["skyPresent"] is not (not no_sky) or t["requestedSky"] is not requested or t["nativeRepeatControl"] is not self.native:
            raise NotVerified("Actual/requested/native scene role mismatch: " + name)
        removed = name.startswith("trans-removed-")
        if removed:
            expected_role = (name.removeprefix("trans-removed-"), "none", True, "removed")
        else:
            parts = name.split("-"); expected_role = (parts[2], parts[3], parts[4] == "marker", "baseline")
        if (t["front"], t["protection"], t["behind"], t["role"]) != expected_role or type(t["behind"]) is not bool:
            raise NotVerified("Scene name/front/protection/marker role mismatch: " + name)
        if t["serverSkyBlockCount"] != (0 if no_sky else 88):
            raise NotVerified("Server target sky count mismatch: " + name)
        self.physical[name] = physical_evidence(data, self.native, name)
        for key, shape in (("camera", (3,)), ("inverseDisplayViewProjection", (16,)), ("inverseViewProjection", (16,)),
                           ("rasterScale", (2,)), ("rasterJitter", (2,)), ("targetViewport", (4,)), ("activeViewport", (4,))):
            finite(data[key], shape, key)
        if min(data["rasterScale"]) <= 0 or not np.allclose(data["rasterJitter"], self.jitter, atol=1e-10, rtol=0):
            raise NotVerified("Actual jitter differs from frozen captureJitterBits: " + name)
        if data["resourceReloadPending"] is not False or data["initialGlError"] != 0 or data["finalGlError"] != 0 or data["itrpBridgeFailure"] is not None:
            raise NotVerified("Unstable/failed render capture: " + name)
        phase(O.Scene(name, data))
        scene = O.Scene(name, data)
        for key, suffix in (("final", ""), ("before", "-before"), ("opaque", "-opaque"), ("depth", "-depth-change"), ("hand", "-post-hand-mask")):
            image_path = self.results / (name + suffix + ".png"); self.file_hash(image_path)
            scene.images[key] = O.rgb(image_path)
            self.sources.observe(image_path.resolve(), self.root, scene.images[key] if key == "final" else None)
        shape = (data["height"], data["width"]); active = (data["opaqueHeight"], data["opaqueWidth"])
        if scene.images["final"].shape[:2] != shape or any(scene.images[k].shape[:2] != active for k in ("before", "opaque", "depth", "hand")):
            raise NotVerified("Capture pixel grid mismatch: " + name)
        if no_sky and (data["blocks"] != 0 or data["vertices"] != 0 or data["itrpMaterialWrites"] is not False
                       or data["itrpBackgroundInjected"] is not False or data["itrpInjectionCount"] != 0
                       or data["depthChangedPixels"] != 0 or np.any(scene.images["depth"])
                       or not np.array_equal(scene.images["before"], scene.images["opaque"])):
            raise NotVerified("NoSky contaminated: require blocks=vertices=depth=0, bridge=false, before=opaque: " + name
                              + " observed=" + json.dumps({k: data[k] for k in ("blocks", "vertices", "depthChangedPixels", "itrpMaterialWrites", "itrpBackgroundInjected", "itrpInjectionCount")}))
        if not no_sky and (data["blocks"] < 88 or data["vertices"] <= 0 or data["depthChangedPixels"] <= 0 or not np.any(scene.images["depth"])):
            raise NotVerified("SkyOn target lacks positive actual geometry/depth evidence: " + name)
        if cache: self.cache[name] = scene
        return scene


def roi_for(scene):
    m, t = scene.meta, scene.t; shape = (m["height"], m["width"])
    g = O.geometry(scene, shape); scale = float(min(m["rasterScale"]))
    margin = max(1, int(np.ceil(LIMITS["margin_active"] / scale)))
    hand = O.map_active_mask(scene.images["hand"][..., 0] > 0, m, shape)
    if hand.mean() > LIMITS["max_hand_fraction"]: raise NotVerified("Unbounded hand exclusion")
    safe = O.erode(g["sky"], margin) & ~O.dilate(hand, max(1, margin // 2))
    region = safe & O.erode(g["transparent"], margin) if t["front"] != "none" else safe
    return region & O.dilate(g["marker"], margin + 2), O.marker_geometry(scene, shape), scale


def phase(scene):
    m = scene.meta; required(m, ("dimension", "displaySky"), scene.name)
    sky = m["displaySky"]
    keys = ("timeSource", "dayTime", "phase", "nativePhase", "moonPhase", "nativeMoonPhase", "rain", "thunder", "gameTime", "nativeFixedTime", "hasSkylight", "nativeSkyType")
    required(sky, keys, "displaySky " + scene.name)
    if m["dimension"] != "minecraft:overworld" or sky["timeSource"] != "local-sky":
        raise NotVerified("Unreviewed dimension/time policy: " + scene.name)
    finite([sky[k] for k in ("dayTime", "phase", "nativePhase", "moonPhase", "nativeMoonPhase", "rain", "thunder", "gameTime")], (8,), "displaySky")
    return dict(dimension=m["dimension"], policy=sky["timeSource"], dayPhase=sky["dayTime"] % 24000,
                phase=sky["phase"], nativePhase=sky["nativePhase"], moon=sky["moonPhase"], nativeMoon=sky["nativeMoonPhase"],
                rain=sky["rain"], thunder=sky["thunder"], nativeFixedTime=sky["nativeFixedTime"], hasSkylight=sky["hasSkylight"], nativeSkyType=sky["nativeSkyType"])


def equivalent(first, scenes, bare=False):
    for scene in scenes:
        version = first.t.get("fixtureGeometryVersion", 1)
        if version != scene.t.get("fixtureGeometryVersion", 1) or (version >= 2 and first.t["markerPattern"] != scene.t["markerPattern"]):
            raise NotVerified("Different physical geometry protocol/marker pattern: " + scene.name)
        if bare:
            keys = ("skyBounds", "behindBounds", "pillarBounds", "markerBoxes", "markerMaterial", "protection")
            if any(first.t[k] != scene.t[k] for k in keys): raise NotVerified("Bare physical geometry differs: " + scene.name)
        elif not O.matching(first, scene): raise NotVerified("Pose/AABB/marker material mismatch: " + scene.name)
        for key in ("camera", "inverseDisplayViewProjection", "rasterScale", "rasterJitter", "targetViewport", "activeViewport", "width", "height", "opaqueWidth", "opaqueHeight"):
            if not np.allclose(first.meta[key], scene.meta[key], atol=2e-5 if key in ("camera", "inverseDisplayViewProjection") else 1e-9, rtol=0):
                raise NotVerified("Actual pose/raster/jitter mismatch: " + key + " " + scene.name)
        for key in ("cameraType", "heldItem", "offhandItem"):
            if first.meta[key] != scene.meta[key]: raise NotVerified("Actual camera/item pose differs: " + scene.name)
        p0, p1 = phase(first), phase(scene)
        if any(not np.isclose(p0[k], p1[k], atol=2e-5, rtol=0) if type(p0[k]) in (int, float) else p0[k] != p1[k] for k in p0):
            raise NotVerified("Actual dimension/day phase/weather policy mismatch: " + scene.name)


def lp_features(delta, roi, scale):
    radius = int(np.ceil(LIMITS["marker_shift_active"] / scale))
    radii = [tuple(max(1, int(np.ceil(v / scale))) for v in band) for band in BANDS]
    border = radius + max(pair[1] for pair in radii) + 3
    y, x = np.nonzero(roi)
    if len(y) < LIMITS["min_pixels"]: raise NotVerified("Too few independent ROI pixels")
    crop = np.s_[max(0, y.min()-border):min(len(roi), y.max()+border+1), max(0, x.min()-border):min(roi.shape[1], x.max()+border+1)]
    mask = roi[crop]; residual = O.remove_affine(delta[crop].astype(float), mask)
    values = {"signed-shape": residual, "energy-envelope": np.sqrt(np.mean(residual**2, -1))[..., None]}
    features = {(family, band): O.box_average(value, small)-O.box_average(value, large)
                for family, value in values.items() for band, (small, large) in zip(BANDS, radii)}
    return features, mask


def matches(template, residual, mask, scale, allowed=None):
    radius = int(np.ceil(LIMITS["marker_shift_active"] / scale)); step = max(1, radius // 3)
    shifts = sorted(set(range(-radius, radius+1, step)) | {0, radius}); stride = max(1, int(round(2 / scale)))
    support, best = defaultdict(set), {}
    h, w = mask.shape
    for (family, band), ta in template.items():
        if allowed is not None and band not in allowed.get(family, set()): continue
        rb = residual[(family, band)]
        for dy, dx in itertools.product(shifts, repeat=2):
            ry, rx = slice(max(0, dy), min(h, h+dy), stride), slice(max(0, dx), min(w, w+dx), stride)
            ty, tx = slice(max(0, -dy), min(h, h-dy), stride), slice(max(0, -dx), min(w, w-dx), stride)
            valid = mask[ry, rx]
            if valid.sum() < LIMITS["min_pixels"]: continue
            a, b = ta[ty, tx][valid], rb[ry, rx][valid]; a, b = a-a.mean(0), b-b.mean(0)
            aa, bb, cross = np.mean(a*a, 0), np.mean(b*b, 0), np.mean(a*b, 0)
            rho = np.abs(cross)/np.maximum(np.sqrt(aa*bb), 1e-12); gain = np.abs(cross)/np.maximum(aa, 1e-12)
            for channel in range(len(rho)):
                rms_a, rms_b = float(np.sqrt(aa[channel])), float(np.sqrt(bb[channel]))
                if rms_a < LIMITS["shape_reference_rms"]: continue
                score = min(float(rho[channel])/LIMITS["shape_correlation"], float(gain[channel])/LIMITS["shape_gain"], rms_b/LIMITS["shape_residual_rms"])
                key = family + ":" + str(band)
                record = dict(correlation=float(rho[channel]), gain=float(gain[channel]), referenceRms=rms_a,
                              residualRms=rms_b, shift=[dx, dy], channel=channel, score=score)
                if key not in best or score > best[key]["score"]: best[key] = record
                if rho[channel] >= LIMITS["shape_correlation"] and gain[channel] >= LIMITS["shape_gain"] and rms_b >= LIMITS["shape_residual_rms"]:
                    support[(family, dx, dy)].add(band)
    coherent = [dict(family=family, shift=[dx, dy], bands=[list(b) for b in sorted(bands)])
                for (family, dx, dy), bands in support.items() if len(bands) >= LIMITS["shape_min_bands"]]
    return dict(matched=bool(coherent), coherent=coherent, best=best)


def evaluate_arrays(states, roi, anchor, scale, bare_pairs):
    """Reference-first admissibility. Bare positive controls retain OLD geometry flags."""
    if len(states) not in (4, 8): raise NotVerified("Expected four reference or eight full states")
    if not bare_pairs or len(bare_pairs) < 2: raise NotVerified("Both references need bare physical-marker positives")
    if any(x.shape != states[0].shape or not np.all(np.isfinite(x)) for x in states): raise NotVerified("Invalid array grids/values")
    values = [x.astype(float) for x in states]
    a_m, a_e, b_m, b_e = values[:4]
    features = [lp_features(delta, roi, scale) for delta in (a_m-a_e, b_m-b_e, a_m-b_m, a_e-b_e)]
    f = [value[0] for value in features]; mask = features[0][1]
    ab, ba = matches(f[0], f[1], mask, scale), matches(f[1], f[0], mask, scale)
    forward, reverse = defaultdict(set), defaultdict(set)
    for item in ab["coherent"]:
        for band in item["bands"]: forward[item["family"]].add(tuple(band))
    for item in ba["coherent"]:
        for band in item["bands"]: reverse[item["family"]].add(tuple(band))
    allowed = {family: bands & reverse[family] for family, bands in forward.items() if len(bands & reverse[family]) >= LIMITS["shape_min_bands"]}
    negatives = [dict(control=label, template=letter, **matches(f[i], f[j], mask, scale, allowed))
                 for label, j in (("M_A-M_B", 2), ("E_A-E_B", 3)) for i, letter in enumerate(("A", "B"))]
    bare_records = []
    for marker, empty in bare_pairs:
        _, evidence = O.marker_metrics(marker, empty, marker, empty, roi, LIMITS, scale, geometry=anchor)
        bare_records.append(evidence)
    ref_valid = bool(allowed and ab["matched"] and ba["matched"]
                     and all(x["multiScaleEvidenceValid"] and x["multiscaleLeakDetected"] for x in bare_records)
                     and all(not x["matched"] for x in negatives))
    ref_failures = []
    if not allowed or not ab["matched"] or not ba["matched"]: ref_failures.append("A/B repeat response lacks bidirectional coherent >=2-band support at fixed RMS/correlation/gain limits")
    ref_failures += ["bare physical-marker geometry positive failed at control index " + str(i) for i, x in enumerate(bare_records) if not (x["multiScaleEvidenceValid"] and x["multiscaleLeakDetected"])]
    ref_failures += ["same-state negative matched: " + x["control"] + " template=" + x["template"] for x in negatives if x["matched"]]
    out = dict(evidenceType=EVIDENCE_TYPE, referenceRepeatAdmissible=ref_valid, referenceRejectionReasons=ref_failures, empiricalRepeatEvidenceValid=False,
               repeatAB=ab, repeatBA=ba, admissibleBands={k: [list(b) for b in sorted(v)] for k, v in allowed.items()},
               sameStateNegativeControls=negatives, bareGeometryPositiveControls=bare_records,
               legacyWaterFlags=None, leakDetected=None, status="NOT_VERIFIED")
    if len(states) == 4 or not ref_valid: return out
    h_m, h_e, s_m, s_e = values[4:]
    h = lp_features(h_m-h_e, roi, scale)[0]
    holdouts = [matches(f[index], h, mask, scale, allowed) for index in (0, 1)]
    extra_negatives = []
    for label, delta in (("M_A-M_H", a_m-h_m), ("E_A-E_H", a_e-h_e), ("M_B-M_H", b_m-h_m), ("E_B-E_H", b_e-h_e)):
        residual = lp_features(delta, roi, scale)[0]
        extra_negatives += [dict(control=label, template=letter, **matches(f[i], residual, mask, scale, allowed)) for i, letter in enumerate(("A", "B"))]
    positive = matches(h, h, mask, scale, allowed)
    out.update(candidateNoSkyHoldout=holdouts, holdoutSameStateNegativeControls=extra_negatives, holdoutLeakPositiveControl=positive)
    valid = all(x["matched"] for x in holdouts) and positive["matched"] and all(not x["matched"] for x in extra_negatives)
    out["empiricalRepeatEvidenceValid"] = bool(valid)
    out["holdoutRejectionReasons"] = (["NoSky holdout does not match both independent references"] if not all(x["matched"] for x in holdouts) else [])
    if not positive["matched"]: out["holdoutRejectionReasons"].append("NoSky holdout fails its own fixed-threshold leak-positive control")
    out["holdoutRejectionReasons"] += ["holdout same-state negative matched: " + x["control"] + " template=" + x["template"] for x in extra_negatives if x["matched"]]
    if valid:
        sky = lp_features(s_m-s_e, roi, scale)[0]
        target = [matches(f[index], sky, mask, scale, allowed) for index in (0, 1)]
        out["skyOnComparisons"] = target
        if target[0]["matched"] != target[1]["matched"]: return out
        out["leakDetected"] = bool(target[0]["matched"])
        out["status"] = "MARKER_INFLUENCE_DETECTED" if out["leakDetected"] else "NO_MATCH_ABOVE_FIXED_THRESHOLDS"
    return out


def proof(ref_a, ref_b, candidate, expected_jar, front="water", protections=O.PROTECTIONS, reference_slot="sky", project=ROOT, references_only=False):
    result = dict(schemaVersion=2, kind="independent empirical repeat diagnostic; NOT final acceptance/audit",
                  evidenceType=EVIDENCE_TYPE, passed=False, status="NOT_VERIFIED", referencesOnly=references_only,
                  expectedJarSha256=expected_jar, fixedThresholds=LIMITS, fixedActiveBands=[list(x) for x in BANDS],
                  referenceSlot=reference_slot, roiSource="A/B NoSky physical AABBs and reference hand exclusion ONLY; fixed before candidate read",
                  cases={}, errors=[], inputs={}, toolSha256={str(p): sha(p) for p in (Path(__file__), ROOT / "tools/check-transparency-regression.py", ROOT / "tools/check-portal-regression.py", ROOT / "tools/settled-transparency-protocol.py")})
    sources = Sources(); result["sourceObservations"] = sources.observations
    try:
        if not re.fullmatch(r"[0-9a-fA-F]{64}", expected_jar): raise NotVerified("Explicit expected jar SHA-256 required")
        if front not in O.FRONTS or reference_slot not in ("open", "sky") or not protections or any(p not in O.PROTECTIONS for p in protections):
            raise NotVerified("Unreviewed front/protection/reference slot")
        expected_jar = expected_jar.lower()
        paths = [Path(p).resolve(strict=True) for p in ((ref_a, ref_b) if references_only else (ref_a, ref_b, candidate))]
        roots = [(p.parent if p.name == "results" else p) for p in paths]
        if len(set(roots)) != len(roots) or any(a.is_relative_to(b) or b.is_relative_to(a) for a, b in itertools.combinations(roots, 2)):
            raise NotVerified("Distinct non-nested real fixture roots required; same-source reuse")
        fixtures = [Fixture(p, expected_jar, True if i < 2 else False, project, result["inputs"], sources) for i, p in enumerate(paths)]
        if any(f.signature != fixtures[0].signature for f in fixtures[1:]): raise NotVerified("Actual artifact/pack/settings/profile/jitter policy mismatch")
        result["actualEnvironment"] = fixtures[0].signature
        result["fixtureRoots"] = [str(f.root) for f in fixtures]
        result["semanticSettings"] = [f.settings for f in fixtures]
        result["versionedPhysicalEvidence"] = {str(f.root): f.physical for f in fixtures}
        result["distantGenerationEvidence"] = {str(f.root): f.distant_policy for f in fixtures}
        result["settledProtocolEvidence"] = {str(f.root): f.protocol for f in fixtures}
        for protection in protections:
            values = dict(status="NOT_VERIFIED", evidenceType=EVIDENCE_TYPE, errors=[])
            result["cases"][protection] = values
            try:
                refs = [fixture.scene(f"trans-{reference_slot}-{front}-{protection}-{state}", True, reference_slot == "sky")
                        for fixture in fixtures[:2] for state in ("marker", "empty")]
                equivalent(refs[0], refs[1:])
                masks = [roi_for(scene) for scene in refs]
                roi = np.logical_and.reduce([x[0] for x in masks]); anchor = np.logical_and.reduce([x[1] for x in masks]); scale = masks[0][2]
                if min(int((roi & anchor).sum()), int((roi & ~anchor).sum())) < LIMITS["min_pixels"]:
                    raise NotVerified("Insufficient independent object/gap samples")
                # Both slots: catches absent first physical initialization even when
                # later same-timeline marker/empty captures happen to repeat well.
                bare_pairs, bare_labels = [], []
                for fixture in fixtures[:2]:
                    for slot in ("open", "sky"):
                        bare = [fixture.scene(f"trans-{slot}-none-{protection}-{state}", True, slot == "sky") for state in ("marker", "empty")]
                        equivalent(refs[0], bare, bare=True)
                        bare_pairs.append(tuple(x.images["final"] for x in bare)); bare_labels.append(dict(root=str(fixture.root), slot=slot))
                values.update(evaluate_arrays([x.images["final"] for x in refs], roi, anchor, scale, bare_pairs))
                values.update(bareControlSources=bare_labels, roiPixels=int(roi.sum()), roiSha256=hash_array(roi),
                              actualPhase=phase(refs[0]), referenceDeltaSha256=[hash_array(refs[i].images["final"].astype(np.int16)-refs[i+1].images["final"].astype(np.int16)) for i in (0, 2)])
                if references_only: continue
                # Still inspect holdout/target metadata when references fail, but
                # never report empirical validity/leak/no-match from bad references.
                more = [fixtures[2].scene(f"trans-{slot}-{front}-{protection}-{state}", slot == "open", slot == "sky")
                        for slot in ("open", "sky") for state in ("marker", "empty")]
                equivalent(refs[0], more)
                advancing_clocks([fixtures[2].physical[x.name] for x in more], "candidate selected NoSky/SkyOn quartet")
                if any(np.any(roi & ~roi_for(scene)[0]) for scene in more):
                    raise NotVerified("Candidate exclusion intersects frozen reference ROI; no target-fitted shrinking")
                _, old = O.marker_metrics(*(x.images["final"] for x in (more[2], more[3], more[0], more[1])), roi, LIMITS, scale, geometry=anchor)
                old_flags = {key: old[key] for key in ("legacyEvidenceValid", "multiScaleEvidenceValid", "evidenceValid", "leakDetected", "legacyLeakDetected", "multiscaleLeakDetected")}
                # Original flags remain an unmodified separate evaluation.
                values["legacyWaterFlags"] = old_flags
                if values["referenceRepeatAdmissible"]:
                    values.update(evaluate_arrays([x.images["final"] for x in refs + more], roi, anchor, scale, bare_pairs))
                    values["legacyWaterFlags"] = old_flags
                values["actualGameTimesNotRequiredEqual"] = [x.meta["displaySky"]["gameTime"] for x in refs + more]
                values["candidateDeltaSha256"] = [hash_array(more[i].images["final"].astype(np.int16)-more[i+1].images["final"].astype(np.int16)) for i in (0, 2)]
            except (OSError, ValueError, KeyError, TypeError, IndexError, struct.error) as error:
                values["status"] = "NOT_VERIFIED"; values["errors"].append(str(error))
                result["errors"].append(protection + ": " + str(error))
        result["referenceRepeatAdmissible"] = bool(result["cases"]) and all(x.get("referenceRepeatAdmissible") is True for x in result["cases"].values())
        result["passed"] = not references_only and not result["errors"] and bool(result["cases"]) and all(x["status"] == "NO_MATCH_ABOVE_FIXED_THRESHOLDS" for x in result["cases"].values())
        if result["passed"]: result["status"] = "EMPIRICAL_REPEAT_SUPPORTED_NO_MATCH_ABOVE_FIXED_THRESHOLDS"
        elif any(x["status"] == "MARKER_INFLUENCE_DETECTED" for x in result["cases"].values()): result["status"] = "MARKER_INFLUENCE_DETECTED"
    except (OSError, ValueError, KeyError, TypeError, IndexError, zipfile.BadZipFile, struct.error) as error:
        result["errors"].append(str(error))
    result["limitations"] = [
        "NOT a final independent audit or old acceptance-gate replacement; synthetic/TEMP tests are not real GPU evidence.",
        "Original legacy/HF and geometry multiscale flags/thresholds stay separate and are never set true by repeat evidence.",
        "Fixed LP/HF/gain/RMS/correlation/two-band limits bound detectability; no match is not literally zero influence.",
        "Repeatability does not uniquely prove marker causality. Responses can include shadows/GI/reflections, not just silhouettes.",
        "A perfectly repeated marker-slot-correlated nuisance can pass even these controls; unique causality needs counterbalanced physical intervention.",
        "Distinct real roots and file identities reject source aliases/hardlinks. Equal pixels alone are only duplicate-content-observation/source-review, not proof of reuse; hashes do not authenticate independent acquisition.",
        "Actual native dimension/dayTime modulo phase/weather are checked; absolute gameTime equality is not substituted for phase.",
        "Geometry v2+ requires exact actual Server/Client fixed-recipe counts from the original oracle. v3 additionally requires owned water scheduling, actual positive water-control counts, separate advancing live clocks, and whole-NoSky root observations on all 51 reference scenes; v1 keeps its historical fields.",
        "Actual installed DH generatorPlan and rendererMode semantics are compared when its TOML exists. Requested disabled generation requires actual DISABLED + unchanged DEFAULT rendering. This empirical diagnostic never establishes dynamic distant-generation/LOD compatibility."]
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("ref-a", "ref-b"): parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--references-only", action="store_true")
    parser.add_argument("--expected-jar-sha256", required=True)
    parser.add_argument("--front", choices=O.FRONTS, default="water")
    parser.add_argument("--protection", action="append", choices=O.PROTECTIONS)
    parser.add_argument("--reference-slot", choices=("open", "sky"), default="sky")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if not args.references_only and args.candidate is None: parser.error("--candidate required unless --references-only")
    output = O.new_report_path(args.output)
    result = proof(args.ref_a, args.ref_b, args.candidate, args.expected_jar_sha256, args.front,
                   args.protection or O.PROTECTIONS, args.reference_slot, references_only=args.references_only)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as stream: json.dump(result, stream, indent=2, allow_nan=False); stream.write("\n")
    print(result["status"], "report:", output, "cases:", len(result["cases"]), "errors:", result["errors"])
    return 0 if result["passed"] else 1


if __name__ == "__main__": raise SystemExit(main())
