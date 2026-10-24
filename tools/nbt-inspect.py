#!/usr/bin/env python3
"""最小 NBT 解析器：读取 1.21 世界 level.dat 的几个关键字段。

用法：python tools/nbt-inspect.py <path-to-level.dat>
只读，不写回。用于判断世界是否创造模式 / 是否允许作弊命令。
"""
from __future__ import annotations

import gzip
import struct
import sys
from pathlib import Path

TAG_NAMES = {
    0: "End", 1: "Byte", 2: "Short", 3: "Int", 4: "Long", 5: "Float",
    6: "Double", 7: "ByteArray", 8: "String", 9: "List", 10: "Compound",
    11: "IntArray", 12: "LongArray",
}


class Reader:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.pos = 0

    def u1(self) -> int:
        v = self.data[self.pos]
        self.pos += 1
        return v

    def i1(self) -> int:
        v = struct.unpack_from(">b", self.data, self.pos)[0]
        self.pos += 1
        return v

    def i2(self) -> int:
        v = struct.unpack_from(">h", self.data, self.pos)[0]
        self.pos += 2
        return v

    def i4(self) -> int:
        v = struct.unpack_from(">i", self.data, self.pos)[0]
        self.pos += 4
        return v

    def i8(self) -> int:
        v = struct.unpack_from(">q", self.data, self.pos)[0]
        self.pos += 8
        return v

    def f4(self) -> float:
        v = struct.unpack_from(">f", self.data, self.pos)[0]
        self.pos += 4
        return v

    def f8(self) -> float:
        v = struct.unpack_from(">d", self.data, self.pos)[0]
        self.pos += 8
        return v

    def string(self) -> str:
        n = struct.unpack_from(">H", self.data, self.pos)[0]
        self.pos += 2
        s = self.data[self.pos:self.pos + n].decode("utf-8", "replace")
        self.pos += n
        return s


def read_payload(r: Reader, tag: int):
    if tag == 1:
        return r.i1()
    if tag == 2:
        return r.i2()
    if tag == 3:
        return r.i4()
    if tag == 4:
        return r.i8()
    if tag == 5:
        return r.f4()
    if tag == 6:
        return r.f8()
    if tag == 7:
        n = r.i4()
        v = r.data[r.pos:r.pos + n]
        r.pos += n
        return v
    if tag == 8:
        return r.string()
    if tag == 9:
        elem = r.u1()
        n = r.i4()
        return [read_payload(r, elem) for _ in range(n)]
    if tag == 10:
        out = {}
        while True:
            t = r.u1()
            if t == 0:
                return out
            name = r.string()
            out[name] = read_payload(r, t)
    if tag == 11:
        n = r.i4()
        v = [r.i4() for _ in range(n)]
        return v
    if tag == 12:
        n = r.i4()
        v = [r.i8() for _ in range(n)]
        return v
    raise ValueError(f"unknown tag {tag} at {r.pos}")


def main() -> None:
    path = Path(sys.argv[1])
    raw = gzip.decompress(path.read_bytes())
    r = Reader(raw)
    tag = r.u1()
    name = r.string()
    root = read_payload(r, tag)
    data = root.get("Data", root)
    print("root:", name, "type:", TAG_NAMES.get(tag))
    for key in ("LevelName", "GameType", "allowCommands", "hardcore", "Difficulty", "lastPlayed"):
        if key in data:
            print(f"{key} = {data[key]!r}")
    print("version:", data.get("Version"))
    print("DataVersion:", data.get("DataVersion"))


if __name__ == "__main__":
    main()
