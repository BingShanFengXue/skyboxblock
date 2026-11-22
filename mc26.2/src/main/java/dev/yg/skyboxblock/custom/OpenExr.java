package dev.yg.skyboxblock.custom;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Small, dependency-free OpenEXR decoder for panorama import.
 *
 * <p>It accepts single-part scanline and tiled files with RGB/RGBA or Y channels, half/float/uint
 * samples, and NONE/ZIP/ZIPS/PIZ compression. A TX file is accepted when its payload is an OpenEXR
 * container (the format emitted by common maketx pipelines). Unsupported deep, multipart,
 * DWAA/DWAB and subsampled channels fail with a diagnostic instead of producing a corrupt sky.
 * The decoded linear pixels are tone-mapped once at startup into the same display-referred sRGB
 * representation used by RadianceHdr.</p>
 */
public final class OpenExr {
    private static final int MAGIC = 0x01312f76;
    private static final int MAX_DIMENSION = 16384;
    private static final long MAX_PIXELS = 16_777_216L;
    private static final double LR = 0.2126;
    private static final double LG = 0.7152;
    private static final double LB = 0.0722;
    private static final double TARGET = 0.45;
    private static final int GRID = 64;

    private OpenExr() { }

    public record Decoded(BufferedImage image, double exposure, double medianLuminance,
                          int width, int height, boolean tiled) { }

    public static boolean looksLike(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && (bytes[0] & 0xff) == 0x76 && (bytes[1] & 0xff) == 0x2f
                && (bytes[2] & 0xff) == 0x31 && (bytes[3] & 0xff) == 0x01;
    }

    public static Decoded decode(byte[] bytes, int maxWidth, int maxHeight) throws IOException {
        if (bytes == null) throw new IllegalArgumentException("EXR 数据为 null");
        if (maxWidth <= 0 || maxHeight <= 0) throw new IllegalArgumentException("EXR 尺寸上限必须为正数");
        Cursor cursor = new Cursor(bytes, 0);
        if (cursor.i32() != MAGIC) throw new IOException("不是 OpenEXR：魔数不匹配");
        int versionFlags = cursor.i32();
        int version = versionFlags & 0xff;
        int flags = versionFlags & ~0xff;
        if (version < 1 || version > 2) throw new IOException("OpenEXR 版本不受支持：" + version);
        if ((flags & 0x1000) != 0) throw new IOException("OpenEXR multipart 文件不受支持");
        if ((flags & 0x8000) != 0) throw new IOException("OpenEXR deep 文件不受支持");

        Header header = readHeader(cursor, (flags & 0x200) != 0, maxWidth, maxHeight);
        float[] r = new float[header.width * header.height];
        float[] g = new float[r.length];
        float[] b = new float[r.length];
        decodePixels(bytes, cursor.pos, header, r, g, b);
        return toneMap(r, g, b, header.width, header.height, header.tiled);
    }

    private static Header readHeader(Cursor cursor, boolean tiled, int maxWidth, int maxHeight)
            throws IOException {
        List<Channel> channels = new ArrayList<>();
        int minX = 0, minY = 0, maxX = -1, maxY = -1;
        int compression = -1;
        TileInfo tile = null;
        while (true) {
            String name = cursor.cstring(256, "属性名");
            if (name.isEmpty()) break;
            String type = cursor.cstring(256, "属性类型 " + name);
            long sizeLong = cursor.u32();
            if (sizeLong > Integer.MAX_VALUE || sizeLong > cursor.remaining())
                throw new IOException("OpenEXR 属性 " + name + " 长度非法：" + sizeLong);
            int size = (int) sizeLong;
            byte[] value = cursor.bytes(size);
            switch (name) {
                case "channels" -> parseChannels(value, channels);
                case "dataWindow" -> {
                    if (size != 16) throw new IOException("OpenEXR dataWindow 长度错误：" + size);
                    IntCursor c = new IntCursor(value);
                    minX = c.i32(); minY = c.i32(); maxX = c.i32(); maxY = c.i32();
                }
                case "compression" -> {
                    if (size < 1) throw new IOException("OpenEXR compression 属性为空");
                    compression = value[0] & 0xff;
                }
                case "tiles" -> {
                    if (size < 9) throw new IOException("OpenEXR tiles 属性过短");
                    IntCursor c = new IntCursor(value);
                    tile = new TileInfo(checkedInt(c.u32(), "tile width"),
                            checkedInt(c.u32(), "tile height"), c.u8());
                }
                default -> { /* displayWindow, lineOrder, pixelAspectRatio and metadata */ }
            }
        }
        if (channels.isEmpty()) throw new IOException("OpenEXR 缺少 channels 属性");
        if (maxX < minX || maxY < minY) throw new IOException("OpenEXR dataWindow 无效");
        long widthLong = (long) maxX - minX + 1L;
        long heightLong = (long) maxY - minY + 1L;
        if (widthLong > MAX_DIMENSION || heightLong > MAX_DIMENSION)
            throw new IOException("OpenEXR 图像边长超过硬上限 " + MAX_DIMENSION);
        long pixels = widthLong * heightLong;
        if (pixels > MAX_PIXELS) throw new IOException("OpenEXR 图像像素超过硬上限 " + MAX_PIXELS);
        if (widthLong > maxWidth || heightLong > maxHeight)
            throw new IOException("OpenEXR 图像 " + widthLong + "x" + heightLong
                    + " 超过调用方上限 " + maxWidth + "x" + maxHeight);
        if (compression < 0) throw new IOException("OpenEXR 缺少 compression 属性");
        if (compression != 0 && compression != 2 && compression != 3 && compression != 4)
            throw new IOException("OpenEXR compression 不受支持：" + compression
                    + "（支持 NONE、ZIPS、ZIP、PIZ）");
        if (tiled && tile == null) throw new IOException("OpenEXR tiled 文件缺少 tiles 属性");
        if (!tiled && tile != null) throw new IOException("OpenEXR tiles 属性与 scanline 版本冲突");
        for (Channel channel : channels) {
            if (channel.xSampling != 1 || channel.ySampling != 1)
                throw new IOException("OpenEXR 通道采样率不受支持：" + channel.name);
            if (channel.pixelType < 0 || channel.pixelType > 2)
                throw new IOException("OpenEXR 通道类型不受支持：" + channel.name);
        }
        ChannelSelector selector = selectChannels(channels);
        return new Header((int) widthLong, (int) heightLong, minX, minY, maxX, maxY,
                channels, selector, compression, tiled, tile);
    }

    private static void parseChannels(byte[] value, List<Channel> output) throws IOException {
        IntCursor c = new IntCursor(value);
        while (c.remaining() > 0) {
            String name = c.cstring(256, "通道名");
            if (name.isEmpty()) break;
            if (c.remaining() < 16) throw new IOException("OpenEXR 通道记录截断：" + name);
            int pixelType = c.i32();
            c.u8(); c.skip(3);
            int xSampling = c.i32();
            int ySampling = c.i32();
            output.add(new Channel(name, pixelType, xSampling, ySampling));
        }
        if (output.isEmpty()) throw new IOException("OpenEXR channels 为空");
    }

    private static ChannelSelector selectChannels(List<Channel> channels) throws IOException {
        int r = find(channels, "R"), g = find(channels, "G"), b = find(channels, "B");
        int y = find(channels, "Y");
        if (r < 0 && g < 0 && b < 0 && y >= 0) return new ChannelSelector(y, y, y);
        if (r < 0 || g < 0 || b < 0)
            throw new IOException("OpenEXR 需要 R/G/B 或 Y 通道；实际通道："
                    + channels.stream().map(Channel::name).toList());
        return new ChannelSelector(r, g, b);
    }

    private static int find(List<Channel> channels, String wanted) {
        for (int i = 0; i < channels.size(); i++) {
            String name = channels.get(i).name;
            int dot = name.lastIndexOf('.');
            String shortName = dot < 0 ? name : name.substring(dot + 1);
            if (shortName.equalsIgnoreCase(wanted)) return i;
        }
        return -1;
    }

    private static void decodePixels(byte[] bytes, int offset, Header h, float[] r, float[] g, float[] b)
            throws IOException {
        Cursor c = new Cursor(bytes, offset);
        if (h.tiled) {
            int count = tileOffsetCount(h.width, h.height, h.tile);
            long[] offsets = new long[count];
            for (int i = 0; i < count; i++) offsets[i] = c.u64();
            for (long chunk : offsets) if (chunk > 0) decodeTile(bytes, chunk, h, r, g, b);
        } else {
            int lines = linesPerChunk(h.compression);
            int count = (h.height + lines - 1) / lines;
            long[] offsets = new long[count];
            for (int i = 0; i < count; i++) offsets[i] = c.u64();
            for (long chunk : offsets) if (chunk > 0) decodeScanlineChunk(bytes, chunk, h, r, g, b);
        }
    }

    private static void decodeScanlineChunk(byte[] bytes, long chunk, Header h,
                                            float[] r, float[] g, float[] b) throws IOException {
        Cursor c = at(bytes, chunk);
        int yStart = c.i32();
        int compressedSize = checkedSize(c.u32(), "scanline chunk");
        byte[] compressed = c.bytes(compressedSize);
        int lines = Math.min(linesPerChunk(h.compression), h.maxY - yStart + 1);
        if (lines <= 0 || yStart < h.minY || yStart > h.maxY)
            throw new IOException("OpenEXR scanline y 越界：" + yStart);
        byte[] raw = decompress(compressed, h, h.width, lines);
        unpackRows(raw, h, h.width, lines, yStart - h.minY, 0, r, g, b);
    }

    private static void decodeTile(byte[] bytes, long chunk, Header h,
                                   float[] r, float[] g, float[] b) throws IOException {
        Cursor c = at(bytes, chunk);
        int tileX = c.i32(), tileY = c.i32();
        int levelX = c.i32(), levelY = c.i32();
        int compressedSize = checkedSize(c.u32(), "tile chunk");
        byte[] compressed = c.bytes(compressedSize);
        if (levelX != 0 || levelY != 0) return;
        int x0 = tileX * h.tile.xSize + h.minX;
        int y0 = tileY * h.tile.ySize + h.minY;
        if (x0 > h.maxX || y0 > h.maxY) return;
        int width = Math.min(h.tile.xSize, h.maxX - x0 + 1);
        int height = Math.min(h.tile.ySize, h.maxY - y0 + 1);
        byte[] raw = decompress(compressed, h, width, height);
        unpackRows(raw, h, width, height, y0 - h.minY, x0 - h.minX, r, g, b);
    }

    private static void unpackRows(byte[] raw, Header h, int width, int height,
                                   int yOffset, int xOffset, float[] r, float[] g, float[] b) throws IOException {
        IntCursor cursor = new IntCursor(raw);
        int rowBytes = width;
        for (int row = 0; row < height; row++) {
            int y = yOffset + row;
            for (int channelIndex = 0; channelIndex < h.channels.size(); channelIndex++) {
                Channel channel = h.channels.get(channelIndex);
                boolean keep = channelIndex == h.selector.r || channelIndex == h.selector.g || channelIndex == h.selector.b;
                for (int x = 0; x < width; x++) {
                    float sample = readSample(cursor, channel.pixelType);
                    if (!keep) continue;
                    int targetX = xOffset + x;
                    if (targetX < 0 || targetX >= h.width || y < 0 || y >= h.height) continue;
                    int target = y * h.width + targetX;
                    if (channelIndex == h.selector.r) r[target] = sample;
                    if (channelIndex == h.selector.g) g[target] = sample;
                    if (channelIndex == h.selector.b) b[target] = sample;
                }
            }
        }
        if (cursor.remaining() != 0) {
            // Extra bytes are allowed by some writers for padding; all required samples were consumed.
        }
    }

    private static float readSample(IntCursor cursor, int type) throws IOException {
        return switch (type) {
            case 0 -> (float) cursor.u32();
            case 1 -> half(cursor.u16());
            case 2 -> Float.intBitsToFloat(cursor.i32());
            default -> throw new IOException("OpenEXR sample type 不受支持：" + type);
        };
    }

    private static byte[] decompress(byte[] compressed, Header h, int width, int height) throws IOException {
        int expected = expectedBytes(h, width, height);
        // EXR writers store the original bytes if the chunk would not shrink under compression.
        if (compressed.length == expected) return compressed;
        if (h.compression == 0 || compressed.length > expected)
            throw new IOException("OpenEXR 数据块长度错误：" + compressed.length + "/" + expected);
        if (h.compression == 4) {
            int[] channelWords = h.channels.stream().mapToInt(channel -> channel.bytes() / 2).toArray();
            return OpenExrPiz.decode(compressed, expected, width, height, channelWords);
        }
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        byte[] packed = new byte[expected];
        try {
            int written = inflater.inflate(packed);
            if (written != expected || !inflater.finished()) throw new IOException("OpenEXR ZIP 数据长度错误：" + written + "/" + expected);
        } catch (DataFormatException e) {
            throw new IOException("OpenEXR ZIP 数据损坏", e);
        } finally {
            inflater.end();
        }
        // OpenEXR writers reorder even/odd bytes, then predict across that reordered stream.
        for (int i = 1; i < expected; i++) packed[i] = (byte) (packed[i] + packed[i - 1] - 128);
        byte[] restored = new byte[expected];
        int half = (expected + 1) / 2;
        for (int i = 0; i < half; i++) restored[i * 2] = packed[i];
        for (int i = half; i < expected; i++) restored[(i - half) * 2 + 1] = packed[i];
        return restored;
    }

    private static int expectedBytes(Header h, int width, int height) throws IOException {
        long perPixel = 0;
        for (Channel c : h.channels) perPixel += c.bytes();
        long total = perPixel * width * (long) height;
        if (total > Integer.MAX_VALUE) throw new IOException("OpenEXR 数据块太大");
        return (int) total;
    }

    private static int linesPerChunk(int compression) {
        return switch (compression) {
            case 3 -> 16; // ZIP
            case 4 -> 32; // PIZ
            default -> 1;
        };
    }

    private static int tileOffsetCount(int width, int height, TileInfo tile) throws IOException {
        if (tile == null || tile.xSize <= 0 || tile.ySize <= 0) throw new IOException("OpenEXR tile 尺寸无效");
        int x = (width + tile.xSize - 1) / tile.xSize;
        int y = (height + tile.ySize - 1) / tile.ySize;
        int mode = tile.mode & 0x0f;
        if (mode == 0) return x * y;
        if (mode == 1) {
            int levels = 1 + floorLog2(Math.max(width, height));
            int count = 0;
            for (int level = 0; level < levels; level++) count += levelTiles(width, tile.xSize, level) * levelTiles(height, tile.ySize, level);
            return count;
        }
        if (mode == 2) {
            int lx = 1 + floorLog2(width), ly = 1 + floorLog2(height), count = 0;
            for (int yLevel = 0; yLevel < ly; yLevel++) for (int xLevel = 0; xLevel < lx; xLevel++)
                count += levelTiles(width, tile.xSize, xLevel) * levelTiles(height, tile.ySize, yLevel);
            return count;
        }
        throw new IOException("OpenEXR tile level mode 不受支持：" + mode);
    }

    private static int levelTiles(int size, int tile, int level) {
        int levelSize = Math.max(1, size >> level);
        return (levelSize + tile - 1) / tile;
    }

    private static int floorLog2(int value) { return 31 - Integer.numberOfLeadingZeros(value); }

    private static Decoded toneMap(float[] r, float[] g, float[] b, int width, int height, boolean tiled) {
        int sx = Math.max(1, (width + GRID - 1) / GRID), sy = Math.max(1, (height + GRID - 1) / GRID);
        double[] samples = new double[((width - 1) / sx + 1) * ((height - 1) / sy + 1)];
        int at = 0;
        for (int y = 0; y < height; y += sy) for (int x = 0; x < width; x += sx) {
            int i = y * width + x;
            samples[at++] = luminance(r[i], g[i], b[i]);
        }
        Arrays.sort(samples);
        double median = (samples.length & 1) == 1 ? samples[samples.length / 2]
                : (samples[samples.length / 2 - 1] + samples[samples.length / 2]) * .5;
        double exposure = TARGET / Math.max(median, 1e-6);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < pixels.length; i++) {
            double lum = luminance(r[i], g[i], b[i]);
            if (!(lum > 0.0) || !Double.isFinite(lum)) { pixels[i] = 0xff000000; continue; }
            double gain = exposure / (1.0 + lum * exposure);
            pixels[i] = 0xff000000 | (srgb(r[i] * gain) << 16) | (srgb(g[i] * gain) << 8) | srgb(b[i] * gain);
        }
        return new Decoded(image, exposure, median, width, height, tiled);
    }

    private static double luminance(float r, float g, float b) {
        return Math.max(0.0, LR * finite(r) + LG * finite(g) + LB * finite(b));
    }
    private static double finite(float value) { return Float.isFinite(value) && value > 0 ? value : 0; }
    private static int srgb(double linear) {
        if (!(linear > 0.0) || !Double.isFinite(linear)) return 0;
        double encoded = linear <= .0031308 ? 12.92 * linear : 1.055 * Math.pow(linear, 1 / 2.4) - .055;
        return Math.max(0, Math.min(255, (int) Math.round(encoded * 255)));
    }

    private record Header(int width, int height, int minX, int minY, int maxX, int maxY,
                          List<Channel> channels, ChannelSelector selector, int compression,
                          boolean tiled, TileInfo tile) { }
    private record Channel(String name, int pixelType, int xSampling, int ySampling) {
        int bytes() { return pixelType == 1 ? 2 : 4; }
    }
    private record ChannelSelector(int r, int g, int b) { }
    private record TileInfo(int xSize, int ySize, int mode) { }

    private static int checkedInt(long value, String what) throws IOException {
        if (value > Integer.MAX_VALUE) throw new IOException("OpenEXR " + what + " 超过 int 范围");
        return (int) value;
    }

    private static int checkedSize(long value, String what) throws IOException {
        if (value > Integer.MAX_VALUE) throw new IOException("OpenEXR " + what + " 太大");
        return (int) value;
    }
    private static Cursor at(byte[] data, long offset) throws IOException {
        if (offset < 0 || offset > data.length - 1) throw new IOException("OpenEXR chunk offset 越界：" + offset);
        return new Cursor(data, (int) offset);
    }

    private static float half(int bits) {
        int sign = (bits >>> 15) & 1, exponent = (bits >>> 10) & 0x1f, fraction = bits & 0x3ff;
        if (exponent == 0) return (float) ((sign == 0 ? 1 : -1) * Math.scalb(fraction / 1024.0, -14));
        if (exponent == 31) return fraction == 0 ? (sign == 0 ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY) : Float.NaN;
        return (float) ((sign == 0 ? 1 : -1) * Math.scalb(1.0 + fraction / 1024.0, exponent - 15));
    }

    private static final class Cursor {
        final byte[] data; int pos;
        Cursor(byte[] data, int pos) { this.data = data; this.pos = pos; }
        int remaining() { return data.length - pos; }
        int u8() throws IOException { if (remaining() < 1) throw new IOException("OpenEXR 数据截断"); return data[pos++] & 0xff; }
        int i32() throws IOException { return u32Int(); }
        int u32Int() throws IOException { if (remaining() < 4) throw new IOException("OpenEXR 数据截断"); int v=(data[pos]&255)|((data[pos+1]&255)<<8)|((data[pos+2]&255)<<16)|((data[pos+3]&255)<<24);pos+=4;return v; }
        long u32() throws IOException { return Integer.toUnsignedLong(u32Int()); }
        long u64() throws IOException { long lo=u32(),hi=u32();return lo|(hi<<32); }
        String cstring(int max, String what) throws IOException { int start=pos;while (pos<data.length && data[pos]!=0 && pos-start<max)pos++;if(pos>=data.length||pos-start>=max)throw new IOException("OpenEXR "+what+"过长或未结束");String s=new String(data,start,pos-start,java.nio.charset.StandardCharsets.US_ASCII);pos++;return s; }
        byte[] bytes(int count) throws IOException { if(count<0||remaining()<count)throw new IOException("OpenEXR 数据截断");byte[] out=Arrays.copyOfRange(data,pos,pos+count);pos+=count;return out; }
    }
    private static final class IntCursor {
        final byte[] data; int pos;
        IntCursor(byte[] data) { this.data=data; }
        int remaining(){return data.length-pos;}
        int u8(){if(remaining()<1)throw new IllegalArgumentException("EXR attribute truncated");return data[pos++]&255;}
        int u16(){return u8()|(u8()<<8);}
        long u32(){return Integer.toUnsignedLong(i32());}
        int i32(){return u8()|(u8()<<8)|(u8()<<16)|(u8()<<24);}
        String cstring(int max,String what)throws IOException{int start=pos;while(pos<data.length&&data[pos]!=0&&pos-start<max)pos++;if(pos>=data.length||pos-start>=max)throw new IOException("OpenEXR "+what+"过长");String s=new String(data,start,pos-start,java.nio.charset.StandardCharsets.US_ASCII);pos++;return s;}
        void skip(int count){if(count<0||remaining()<count)throw new IllegalArgumentException("EXR attribute truncated");pos+=count;}
    }
}
