package dev.yg.skyboxblock.custom;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.Deflater;

/** Pure-JDK OpenEXR/TX regression, including fixtures written by the official OpenEXR library. */
public final class OpenExrRegression {
    private static int checks;
    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }
    private static void putInt(ByteArrayOutputStream out, int value) {
        out.write(value & 255); out.write((value >>> 8) & 255); out.write((value >>> 16) & 255); out.write((value >>> 24) & 255);
    }
    private static void putLong(ByteArrayOutputStream out, long value) {
        putInt(out, (int) value); putInt(out, (int) (value >>> 32));
    }
    private static void putFloat(ByteArrayOutputStream out, float value) { putInt(out, Float.floatToIntBits(value)); }
    private static void cstring(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.US_ASCII)); out.write(0);
    }
    private static void attr(ByteArrayOutputStream out, String name, String type, byte[] data) {
        cstring(out, name); cstring(out, type); putInt(out, data.length); out.writeBytes(data);
    }
    private static byte[] channelList(int pixelType, String... names) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String name : names) {
            cstring(out, name); putInt(out, pixelType); out.write(0); out.write(0); out.write(0); out.write(0);
            putInt(out, 1); putInt(out, 1);
        }
        out.write(0); return out.toByteArray();
    }
    private static byte[] dataWindow() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); putInt(out, 0); putInt(out, 0); putInt(out, 3); putInt(out, 1); return out.toByteArray();
    }
    public static byte[] sampleExr(boolean zip, boolean gray) { return exr(zip, gray); }

    private static byte[] exr(boolean zip, boolean gray) {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        putInt(file, 0x01312f76); putInt(file, 2);
        attr(file, "channels", "chlist", gray ? channelList(1, "Y") : channelList(2, "B", "G", "R"));
        attr(file, "compression", "compression", new byte[]{(byte) (zip ? 3 : 0)});
        attr(file, "dataWindow", "box2i", dataWindow());
        attr(file, "displayWindow", "box2i", dataWindow());
        attr(file, "lineOrder", "lineOrder", new byte[]{0});
        ByteArrayOutputStream aspect = new ByteArrayOutputStream(); putFloat(aspect, 1); attr(file, "pixelAspectRatio", "float", aspect.toByteArray());
        ByteArrayOutputStream center = new ByteArrayOutputStream(); putFloat(center, 0); putFloat(center, 0); attr(file, "screenWindowCenter", "v2f", center.toByteArray());
        ByteArrayOutputStream width = new ByteArrayOutputStream(); putFloat(width, 1); attr(file, "screenWindowWidth", "float", width.toByteArray());
        file.write(0);
        byte[] raw = new byte[gray ? 4 * 2 * 2 : 4 * 2 * 3 * 4];
        if (gray) {
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            for (int row = 0; row < 2; row++) for (int x = 0; x < 4; x++) b.putShort((short) (0x3C00 + x * 0x0100));
        } else {
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            for (int row = 0; row < 2; row++) for (String channel : new String[]{"B", "G", "R"})
                for (int x = 0; x < 4; x++) b.putFloat(channel.equals("R") ? x / 3F : channel.equals("G") ? row / 1F : .25F);
        }
        byte[] packed = zip ? zip(raw) : raw;
        int rowBytes = gray ? 4 * 2 : 4 * 3 * 4;
        if (zip) {
            long chunkOffset = file.size() + 8;
            putLong(file, chunkOffset);
            putInt(file, 0); putInt(file, packed.length); file.writeBytes(packed);
        } else {
            long first = file.size() + 16;
            long second = first + 8L + rowBytes;
            putLong(file, first); putLong(file, second);
            putInt(file, 0); putInt(file, rowBytes); file.writeBytes(Arrays.copyOfRange(raw, 0, rowBytes));
            putInt(file, 1); putInt(file, rowBytes); file.writeBytes(Arrays.copyOfRange(raw, rowBytes, raw.length));
        }
        return file.toByteArray();
    }
    private static byte[] zip(byte[] raw) {
        byte[] interleaved = new byte[raw.length]; int at = 0;
        for (int i = 0; i < raw.length; i += 2) interleaved[at++] = raw[i];
        for (int i = 1; i < raw.length; i += 2) interleaved[at++] = raw[i];
        for (int i = raw.length - 1; i > 0; i--) interleaved[i] = (byte) (interleaved[i] - interleaved[i - 1] + 128);
        Deflater deflater = new Deflater(); deflater.setInput(interleaved); deflater.finish();
        byte[] compressed = new byte[interleaved.length + 128]; int count = deflater.deflate(compressed); deflater.end();
        return Arrays.copyOf(compressed, count);
    }
    static byte[] fixture(String name) throws IOException {
        try (InputStream in = OpenExrRegression.class.getResourceAsStream("/exr/" + name + ".exr")) {
            if (in == null) throw new IOException("Missing EXR fixture: " + name);
            return in.readAllBytes();
        }
    }

    private static ByteBuffer firstChunk(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.position(8);
        while (b.get() != 0) {
            while (b.get() != 0) { }
            while (b.get() != 0) { }
            int size = b.getInt();
            b.position(b.position() + size);
        }
        return b.position(Math.toIntExact(b.getLong()));
    }

    private static void equivalent(OpenExr.Decoded expected, OpenExr.Decoded actual, String label) {
        BufferedImage a = expected.image(), b = actual.image();
        int w = a.getWidth(), h = a.getHeight();
        check(w == b.getWidth() && h == b.getHeight(), label + " dimensions");
        check(Arrays.equals(a.getRGB(0, 0, w, h, null, 0, w), b.getRGB(0, 0, w, h, null, 0, w)), label + " every pixel matches NONE");
        check(expected.exposure() == actual.exposure() && expected.medianLuminance() == actual.medianLuminance(), label + " tone mapping matches NONE");
    }

    private static void badPiz(byte[] bytes, String diagnostic) throws Exception {
        try {
            OpenExr.decode(bytes, 4096, 2048);
            throw new AssertionError("corrupt PIZ was accepted: " + diagnostic);
        } catch (IOException e) {
            check(e.getMessage().contains("PIZ") && e.getMessage().contains(diagnostic), "PIZ error: " + e.getMessage());
        }
    }

    private static void referenceFixtures() throws Exception {
        OpenExr.Decoded raw = OpenExr.decode(fixture("scan-raw"), 4096, 2048);
        int rowBytes = 37 * (2 + 2 + 4 + 2 + 4);
        byte[] piz = fixture("scan-piz");
        ByteBuffer chunk = firstChunk(piz);
        int packed = chunk.getInt(chunk.position() + 4);
        check(packed < 32 * rowBytes, "first PIZ scanline chunk actually compressed");
        int lastOffset = chunk.getInt(chunk.position() + 8 + packed);
        int lastSize = chunk.getInt(chunk.position() + 12 + packed);
        check(lastOffset == 27 && lastSize == 3 * rowBytes, "partial PIZ chunk stored raw with shifted data window");
        for (String name : new String[]{"scan-piz", "tile-piz", "scan-zip", "scan-zips"}) {
            byte[] bytes = fixture(name);
            OpenExr.Decoded decoded = OpenExr.decode(bytes, 4096, 2048);
            equivalent(raw, decoded, name);
            check(decoded.tiled() == name.startsWith("tile"), name + " storage type");
            if (decoded.tiled()) check(firstChunk(bytes).getInt(firstChunk(bytes).position() + 16) < 35 * 33 * 14,
                    "first edge-sized PIZ tile actually compressed");
        }
        byte[] wide = fixture("wide-piz");
        ByteBuffer wideChunk = firstChunk(wide);
        check(wideChunk.getInt(wideChunk.position() + 4) < 1048 * 32 * 2, "modulo PIZ actually compressed");
        equivalent(OpenExr.decode(fixture("wide-raw"), 4096, 2048), OpenExr.decode(wide, 4096, 2048), "PIZ modulo wavelet");

        int payload = chunk.position() + 8;
        byte[] badBitmap = piz.clone();
        ByteBuffer.wrap(badBitmap).order(ByteOrder.LITTLE_ENDIAN).putShort(payload + 2, (short) 8192);
        badPiz(badBitmap, "范围位图");
        int first = Short.toUnsignedInt(chunk.getShort(payload)), last = Short.toUnsignedInt(chunk.getShort(payload + 2));
        int huffmanSize = payload + 4 + Math.max(0, last - first + 1);
        byte[] badSize = piz.clone();
        ByteBuffer.wrap(badSize).order(ByteOrder.LITTLE_ENDIAN).putInt(huffmanSize, Integer.MAX_VALUE);
        badPiz(badSize, "Huffman 数据长度");
        byte[] badBits = piz.clone();
        ByteBuffer.wrap(badBits).order(ByteOrder.LITTLE_ENDIAN).putInt(huffmanSize + 4 + 12, -1);
        badPiz(badBits, "Huffman 表头");
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        byte[] raw = exr(false, false), zip = exr(true, false), gray = exr(false, true);
        check(OpenExr.looksLike(raw), "EXR magic"); check(!OpenExr.looksLike(new byte[]{1, 2, 3, 4}), "non EXR magic");
        OpenExr.Decoded a = OpenExr.decode(raw, 4096, 2048);
        OpenExr.Decoded b = OpenExr.decode(zip, 4096, 2048);
        check(a.width() == 4 && a.height() == 2, "raw dimensions");
        check(a.image().getRGB(0, 0) != a.image().getRGB(3, 0), "raw gradient preserved");
        for (int y = 0; y < 2; y++) for (int x = 0; x < 4; x++) check(a.image().getRGB(x, y) == b.image().getRGB(x, y), "ZIP equals raw");
        OpenExr.Decoded c = OpenExr.decode(gray, 4096, 2048);
        int pixel = c.image().getRGB(0, 0);
        check(pixel >>> 24 == 0xff, "gray output is opaque");
        check((pixel & 0xffffff) == (pixel & 0xff) * 0x010101, "Y replicated across RGB");
        check(pixel != c.image().getRGB(3, 0), "gray gradient preserved");
        referenceFixtures();
        if (args.length > 0) {
            Path dir = Path.of(args[0]); Files.createDirectories(dir);
            Files.write(dir.resolve("sample.exr"), raw); Files.write(dir.resolve("sample.tx"), zip);
            check(PanoramaImageDecoder.decode(dir.resolve("sample.exr"), 4096, 2048).image().getWidth() == 4, "EXR dispatch");
            check(PanoramaImageDecoder.decode(dir.resolve("sample.tx"), 4096, 2048).image().getWidth() == 4, "ZIP TX dispatch");
            Path pizTx = dir.resolve("piz.tx");
            Files.write(pizTx, fixture("scan-piz"));
            PanoramaImageDecoder.Decoded decoded = PanoramaImageDecoder.decode(pizTx, 4096, 2048);
            check(decoded.format().equals("OpenEXR TX") && decoded.image().getWidth() == 37
                    && decoded.image().getHeight() == 35, "PIZ TX dispatch");
        }
        System.out.println("PASS: " + checks + " EXR/TX assertions");
    }
}
