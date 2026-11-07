import zlib, struct
from pathlib import Path

def read_png16(path):
    data = Path(path).read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8; idat = b""; w=h=bd=ct=0
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos+4])[0]; tag = data[pos+4:pos+8]
        payload = data[pos+8:pos+8+ln]
        if tag == b"IHDR":
            w,h,bd,ct = struct.unpack(">IIBB", payload[:10])
        elif tag == b"IDAT":
            idat += payload
        elif tag == b"IEND":
            break
        pos += 12 + ln
    raw = zlib.decompress(idat)
    return w,h,bd,ct,raw

for name in ("10_png16_sub_lsb_band.png","11_png16_full_ramp.png","02_png16.png"):
    p = Path("tmp-hdr-support-probe/materials")/name
    w,h,bd,ct,raw = read_png16(p)
    stride = w*3*2 + 1
    row = raw[stride* (h//2) + 1 : stride*(h//2)+1+w*3*2]
    vals = struct.unpack(">"+("H"*(w*3)), row)
    reds = vals[0::3]
    print(f"{name}: {w}x{h} bitDepth={bd} colorType={ct} row distinct={len(set(reds))} min={min(reds)} max={max(reds)}")
