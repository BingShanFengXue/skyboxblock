#!/usr/bin/env python3
"""抓 Minecraft 窗口的屏幕内容（不依赖窗口焦点），用于光影下无法从渲染目标取图的情况。

Iris 光影会把画面合成到它自己的帧缓冲里，模组在 END_MAIN 抓到的 main target 可能是空的；
用 Windows 的 PrintWindow(PW_RENDERFULLCONTENT) 直接抓窗口可以绕开这个问题。

用法：
    python tools/capture-window.py docs/screenshots/conditions/itrp-window.png
"""
from __future__ import annotations

import ctypes
import ctypes.wintypes as wintypes
import sys
from pathlib import Path

from PIL import Image

user32 = ctypes.windll.user32
gdi32 = ctypes.windll.gdi32

PW_RENDERFULLCONTENT = 0x00000002
SRCCOPY = 0x00CC0020
DIB_RGB_COLORS = 0


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [
        ("biSize", wintypes.DWORD), ("biWidth", wintypes.LONG), ("biHeight", wintypes.LONG),
        ("biPlanes", wintypes.WORD), ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD),
        ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", wintypes.LONG),
        ("biYPelsPerMeter", wintypes.LONG), ("biClrUsed", wintypes.DWORD), ("biClrImportant", wintypes.DWORD),
    ]


class BITMAPINFO(ctypes.Structure):
    _fields_ = [("bmiHeader", BITMAPINFOHEADER), ("bmiColors", wintypes.DWORD * 3)]


def find_window() -> int | None:
    found: list[int] = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def callback(hwnd, _param):
        length = user32.GetWindowTextLengthW(hwnd)
        if length:
            buffer = ctypes.create_unicode_buffer(length + 1)
            user32.GetWindowTextW(hwnd, buffer, length + 1)
            if buffer.value.startswith("Minecraft"):
                found.append(hwnd)
        return True

    user32.EnumWindows(callback, 0)
    return found[0] if found else None


def capture(hwnd: int) -> Image.Image:
    rect = wintypes.RECT()
    user32.GetClientRect(hwnd, ctypes.byref(rect))
    width, height = rect.right - rect.left, rect.bottom - rect.top
    hdc = user32.GetDC(hwnd)
    mem_dc = gdi32.CreateCompatibleDC(hdc)
    bitmap = gdi32.CreateCompatibleBitmap(hdc, width, height)
    gdi32.SelectObject(mem_dc, bitmap)
    user32.PrintWindow(hwnd, mem_dc, PW_RENDERFULLCONTENT)

    info = BITMAPINFO()
    info.bmiHeader.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    info.bmiHeader.biWidth = width
    info.bmiHeader.biHeight = -height          # 负数 = 自上而下
    info.bmiHeader.biPlanes = 1
    info.bmiHeader.biBitCount = 32
    info.bmiHeader.biCompression = 0
    buffer = ctypes.create_string_buffer(width * height * 4)
    gdi32.GetDIBits(mem_dc, bitmap, 0, height, buffer, ctypes.byref(info), DIB_RGB_COLORS)

    image = Image.frombuffer("RGBA", (width, height), buffer, "raw", "BGRA", 0, 1).convert("RGB")
    gdi32.DeleteObject(bitmap)
    gdi32.DeleteDC(mem_dc)
    user32.ReleaseDC(hwnd, hdc)
    return image


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    hwnd = find_window()
    if hwnd is None:
        print("找不到 Minecraft 窗口")
        return 1
    image = capture(hwnd)
    target = Path(sys.argv[1])
    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target)
    print(f"captured {image.size} -> {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
