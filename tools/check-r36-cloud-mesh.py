#!/usr/bin/env python3
"""Independent R36 CPU evidence, NOT a GPU pass/fail oracle.

Read only the explicitly selected named Minecraft jar. Execute no game classes,
Gradle, classpath writer or production code. Interpret the actual buildClouds
javap instructions with JVM binary32 scalar arithmetic and fake vertex sinks.
Print JSON to stdout; write no report, screenshot, cache or shared test artifact.
"""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
import io
import json
import math
from pathlib import Path
import re
import struct
import subprocess
import zipfile

from PIL import Image

PROJECT = Path(__file__).resolve().parents[1]
DEFAULT_JAR = PROJECT / (
    ".gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-f556725616/"
    "1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/"
    "minecraft-merged-f556725616-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar"
)
DEFAULT_JAVAP = PROJECT.parent / ".jdks/jdk-21.0.12.1+1/bin/javap.exe"


def f32(x):
    return struct.unpack("<f", struct.pack("<f", x))[0]


@dataclass
class Vertex:
    position: tuple
    uv: tuple | None = None
    color: tuple | None = None
    normal: tuple | None = None


class VertexSink:
    def __init__(self):
        self.vertices = []

    def call(self, member, args):
        if member == "addVertex":
            self.vertices.append(Vertex(tuple(args)))
        elif member == "setUv":
            self.vertices[-1].uv = tuple(args)
        elif member == "setColor":
            self.vertices[-1].color = tuple(args)
        elif member == "setNormal":
            self.vertices[-1].normal = tuple(args)
        elif member == "buildOrThrow":
            return self.vertices
        else:
            raise AssertionError("unimplemented vertex call " + member)
        return self


def javap(javap_exe, jar, class_name):
    result = subprocess.run(
        [str(javap_exe), "-classpath", str(jar), "-p", "-c", class_name],
        capture_output=True, text=True, encoding="utf-8", errors="strict", check=True,
    )
    return result.stdout


def extract_method(text, name):
    lines = text.splitlines()
    start = next(i for i, line in enumerate(lines) if re.search(r"\b" + name + r"\(", line))
    code = []
    started = False
    for line in lines[start + 1:]:
        match = re.match(r"\s*(\d+):\s+(\S+)(?:\s+(.*))?$", line)
        if match:
            started = True
            tail = match.group(3) or ""
            arg, _, comment = tail.partition("//")
            code.append((int(match.group(1)), match.group(2), arg.strip(), comment.strip()))
        elif started and (not line.strip() or re.match(r"\s+(public|private|protected)", line)):
            break
    if not code:
        raise AssertionError("method code absent: " + name)
    return code


def execute_builder(code, x, y, z, epsilon_override=None):
    """A deliberately tiny fail-closed JVM interpreter, not a hand-copied mesh."""
    sink = VertexSink()
    local = {0: {"prevCloudsType": "FANCY"}, 1: "Tesselator", 2: x, 4: y, 6: z,
             8: {"x": 1.0, "y": 1.0, "z": 1.0}}
    stack = []
    index = {inst[0]: n for n, inst in enumerate(code)}
    pc = 0
    steps = 0
    while pc < len(code):
        steps += 1
        if steps > 4000000:
            raise AssertionError("instruction budget exceeded")
        offset, op, arg, comment = code[pc]
        pc += 1
        if re.match(r"[afid]load(?:_|$)", op):
            slot = int(op.split("_")[1]) if "_" in op else int(arg)
            stack.append(local[slot])
        elif re.match(r"[afid]store(?:_|$)", op):
            slot = int(op.split("_")[1]) if "_" in op else int(arg)
            local[slot] = stack.pop()
        elif op.startswith("iconst_"):
            stack.append(-1 if op == "iconst_m1" else int(op.rsplit("_", 1)[1]))
        elif op.startswith("fconst_"):
            stack.append(float(op.rsplit("_", 1)[1]))
        elif op in ("bipush", "sipush"):
            stack.append(int(arg))
        elif op.startswith("ldc"):
            match = re.match(r"(float|double|int) ([-+0-9.Ee]+)[fd]?$", comment)
            if not match:
                raise AssertionError((offset, op, comment))
            typ, literal = match.groups()
            val = int(literal) if typ == "int" else float(literal)
            if typ == "float":
                val = f32(val)
                if val == 1 / 1024 and epsilon_override is not None:
                    val = f32(epsilon_override)
            stack.append(val)
        elif op in ("fadd", "fsub", "fmul", "fdiv", "dadd", "dsub", "dmul", "ddiv", "iadd", "imul"):
            b, a = stack.pop(), stack.pop()
            value = {"add": lambda: a + b, "sub": lambda: a - b,
                     "mul": lambda: a * b, "div": lambda: a / b}[op[1:]]()
            stack.append(f32(value) if op[0] == "f" else value)
        elif op in ("i2f", "d2f"):
            stack.append(f32(stack.pop()))
        elif op in ("i2d", "f2d"):
            stack.append(float(stack.pop()))
        elif op in ("d2i", "f2i"):
            stack.append(int(stack.pop()))
        elif op == "getfield":
            field = comment.split("Field ", 1)[1].split(":", 1)[0].rsplit(".", 1)[-1]
            stack.append(stack.pop()[field])
        elif op == "getstatic":
            field = comment.split("Field ", 1)[1].split(":", 1)[0].rsplit(".", 1)[-1]
            stack.append(field)
        elif op in ("fcmpg", "fcmpl"):
            b, a = stack.pop(), stack.pop()
            stack.append((a > b) - (a < b))
        elif op.startswith("if_") or op in ("ifle", "ifgt", "ifge", "iflt", "ifeq", "ifne"):
            if op.startswith("if_icmp") or op.startswith("if_acmp"):
                b, a = stack.pop(), stack.pop()
                condition = op[7:]
            else:
                a, b, condition = stack.pop(), 0, op[2:]
            test = {"eq": lambda: a == b, "ne": lambda: a != b, "le": lambda: a <= b,
                    "lt": lambda: a < b, "ge": lambda: a >= b, "gt": lambda: a > b}[condition]()
            if test:
                pc = index[int(arg)]
        elif op == "goto":
            pc = index[int(arg)]
        elif op == "iinc":
            slot, delta = (int(n.strip()) for n in arg.split(","))
            local[slot] += delta
        elif op == "pop":
            stack.pop()
        elif op == "invokestatic":
            if ".floor:(D)" not in comment:
                raise AssertionError((offset, op, comment))
            result = math.floor(stack.pop())
            stack.append(float(result) if "java/lang/Math" in comment else result)
        elif op in ("invokevirtual", "invokeinterface"):
            method = comment.split("Method ", 1)[-1] if "Method " in comment else comment.split("InterfaceMethod ", 1)[-1]
            owner_member, desc = method.split(":", 1)
            member = owner_member.rsplit(".", 1)[-1]
            signature = desc.split(")", 1)[0][1:]
            types = re.findall(r"\[*L[^;]+;|\[*[ZBCSIJFD]", signature)
            argv = [stack.pop() for _ in types][::-1]
            receiver = stack.pop()
            if member == "begin" and receiver == "Tesselator":
                stack.append(sink)
            elif receiver is sink:
                stack.append(sink.call(member, argv))
            else:
                raise AssertionError((offset, member, receiver))
        elif op == "areturn":
            result = stack.pop()
            assert result is sink.vertices and len(result) % 4 == 0
            assert all(v.uv is not None and v.color is not None and v.normal is not None for v in result)
            return result
        else:
            raise AssertionError((offset, op, arg, comment))
    raise AssertionError("no mesh return")


def quads(vertices):
    return [vertices[i:i + 4] for i in range(0, len(vertices), 4)]


def plane_edge_counts(qs):
    edges = Counter()
    for quad in qs:
        for a, b in zip(quad, quad[1:] + quad[:1]):
            edges[tuple(sorted((a.position, b.position)))] += 1
    return dict(sorted(Counter(edges.values()).items()))


def mesh_summary(vertices):
    qs = quads(vertices)
    normals = Counter(q[0].normal for q in qs)
    top = [q for q in qs if q[0].normal == (0.0, 1.0, 0.0)]
    bottom = [q for q in qs if q[0].normal == (0.0, -1.0, 0.0)]
    side = [q for q in qs if q[0].normal[1] == 0]
    return {
        "vertices": len(vertices), "quads": len(qs), "standard_quad_indices": len(qs) * 6,
        "quads_by_normal": {str(k): v for k, v in sorted(normals.items())},
        "top_y": sorted({v.position[1] for q in top for v in q}),
        "side_y": sorted({v.position[1] for q in side for v in q}),
        "top_edge_multiplicities": plane_edge_counts(top),
        "bottom_edge_multiplicities": plane_edge_counts(bottom),
        "source_float_colors_before_packing": {str(k): next(q[0].color for q in qs if q[0].normal == k) for k in normals},
        "expected_unorm8_colors": {str(k): [int(f32(c * 255.0)) for c in next(q[0].color for q in qs if q[0].normal == k)] for k in normals},
    }


def texture_report(rgba):
    w, h = rgba.size
    px = rgba.load()
    assert (w, h) == (256, 256), "this probe expects the actual vanilla 256x256 clouds texture"
    occupied = lambda x, z: px[x % w, z % h][3] / 255.0 * 0.8 >= 0.1
    transitions = {"x": 0, "z": 0}
    examples = []
    for z in range(h):
        for x in range(w):
            for axis, other in (("x", (x + 1, z)), ("z", (x, z + 1))):
                if occupied(x, z) != occupied(*other):
                    transitions[axis] += 1
                    if len(examples) < 8:
                        examples.append({"axis": axis, "cell": [x, z], "occupancy": [occupied(x, z), occupied(*other)]})
    # Native UV inputs are binary-exact multiples of 1/256; interpolated fragment UV need not be.
    at = f32(0.5)
    bits = struct.unpack("<I", struct.pack("<f", at))[0]
    below = struct.unpack("<f", struct.pack("<I", bits - 1))[0]
    above = struct.unpack("<f", struct.pack("<I", bits + 1))[0]
    return {
        "size": [w, h], "alpha_histogram": dict(sorted(Counter(rgba.getchannel("A").tobytes()).items())),
        "rgb_histogram": {str(k): v for k, v in Counter(px[x, z][:3] for z in range(h) for x in range(w)).most_common(8)},
        "nearest_occupancy_transition_edges": transitions,
        "transition_examples": examples,
        "nearest_at_binary32_boundary": {"uv": [below, at, above], "texel_x": [math.floor(u * w) % w for u in (below, at, above)]},
    }


def depth_risk_table():
    # Explicit illustrative projection, not the live game's queried near/far/depth format.
    n, f = 0.05, 2048.0
    derivative_factor = f * n / (f - n)
    rows = []
    for distance in (16.0, 64.0, 128.0, 512.0, 2048.0):
        rows.append({"eye_distance": distance,
                     "vertical_epsilon_d24_steps": derivative_factor / distance ** 2 / 1024 * (2 ** 24 - 1),
                     "horizontal_epsilon_d24_steps": derivative_factor / distance ** 2 * 12 / 1024 * (2 ** 24 - 1)})
    return {"assumed_near": n, "assumed_far": f, "assumed_depth_bits": 24,
            "note": "first-order eye-depth-aligned bound; use actual transformed normals and depth format on GPU", "rows": rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--javap", type=Path, default=DEFAULT_JAVAP)
    args = parser.parse_args()
    disassembly = javap(args.javap, args.jar, "net.minecraft.client.renderer.LevelRenderer")
    code = extract_method(disassembly, "buildClouds")
    canonical = "\n".join(repr(item) for item in code)
    mesh = execute_builder(code, 0.375, 0.0, 0.625)
    native = mesh_summary(mesh)
    same_cell = execute_builder(code, 0.99999, 0.0, 0.00001)
    next_x = execute_builder(code, 1.00001, 0.0, 0.625)
    assert mesh == same_cell, "fractions should not affect native builder output"
    assert all(a.position == b.position and a.normal == b.normal and a.color == b.color
               and b.uv == (f32(a.uv[0] + 1 / 256), a.uv[1])
               for a, b in zip(mesh, next_x)), "integer X should translate UV by exactly one texel"
    assert native["vertices"] == 5632 and native["quads"] == 1408
    assert native["top_y"] == [4.0 - 1 / 1024]
    assert native["side_y"] == [0.0, 4.0]
    assert native["top_edge_multiplicities"] == {1: 32, 2: 112}
    q = quads(mesh)
    positive_x = next(face for face in q if face[0].normal == (1.0, 0.0, 0.0))
    px = positive_x[0].position[0]
    # A positive side lies epsilon inside its own texture texel, while its upper
    # edge protrudes epsilon above the native top. The two planes intersect.
    assert px == math.floor(px) + 1 - 1 / 1024
    zero = mesh_summary(execute_builder(code, 0.375, 0.0, 0.625, epsilon_override=0))
    cases = (-8.00001, -5.00001, -5.0, -4.00001, -4.0, -0.00001, 0.0, 4.99999, 5.0, 5.00001, 7.99999, 8.0)
    heights = []
    by_cell = {}
    for y in cases:
        cell = math.floor(y / 4)
        if cell not in by_cell:
            by_cell[cell] = mesh_summary(execute_builder(code, 0.375, y, 0.625))
        report = by_cell[cell]
        heights.append({"input_y": y, "cell_y": cell, "mesh_base_y": cell * 4,
                        "bottom_present": "(0.0, -1.0, 0.0)" in report["quads_by_normal"],
                        "top_present": bool(report["top_y"]), "vertices": report["vertices"]})
    with zipfile.ZipFile(args.jar) as archive:
        png = archive.read("assets/minecraft/textures/environment/clouds.png")
        texture = texture_report(Image.open(io.BytesIO(png)).convert("RGBA"))
        shader_sources = {name: archive.read("assets/minecraft/shaders/core/" + name).decode("utf-8")
                          for name in ("rendertype_clouds.vsh", "rendertype_clouds.fsh")}
        metadata_name = "assets/minecraft/textures/environment/clouds.png.mcmeta"
        metadata = archive.read(metadata_name).decode("utf-8") if metadata_name in archive.namelist() else None
    render_type = javap(args.javap, args.jar, "net.minecraft.client.renderer.RenderType")
    render_method = extract_method(render_type, "createClouds")
    report = {
        "scope": "CPU-only extraction from actual jar; no GPU verdict; no game or build execution",
        "jar": str(args.jar.resolve()), "jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest(),
        "buildClouds_instruction_count": len(code), "buildClouds_javap_sha256": hashlib.sha256(canonical.encode()).hexdigest(),
        "native_mesh": native,
        "epsilon_plane_relation": {"epsilon_local": 1 / 1024, "horizontal_world": 12 / 1024,
                                   "top_side_relation": "top intersects side interior, not a simple open slit",
                                   "positive_x_sample": px, "zero_epsilon_top_y": zero["top_y"],
                                   "warning": "zero epsilon creates more exactly intersecting/coplanar fragments; not a fix proof"},
        "height_cases": heights,
        "texture_sha256": hashlib.sha256(png).hexdigest(), "texture_metadata": metadata,
        "texture": texture,
        "native_shader_sources": shader_sources,
        "createClouds_relevant_bytecode": [list(i) for i in render_method if i[0] <= 62],
        "illustrative_depth_precision": depth_risk_table(),
        "double_blend_alpha": [{"CloudOpacity": p, "single_alpha": 0.8 * p,
                                 "twice_alpha": 1 - (1 - 0.8 * p) ** 2} for p in (0.25, 0.5, 1.0)],
        "checks": "PASS exact mesh counts/attributes, shared horizontal edges, epsilon planes, fail-closed VM",
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()