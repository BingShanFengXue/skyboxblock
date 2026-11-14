package dev.yg.skyboxblock.custom;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.Deflater;

/** Pure-JDK OpenEXR/TX decoder regression with raw, ZIP and grayscale half samples. */
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
        attr(file, "channels", "chlist", channelList(gray ? 1 : 2, gray ? "Y" : "B", gray ? "" : "G", gray ? "" : "R"));
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
        byte[] predicted = raw.clone();
        for (int i = predicted.length - 1; i > 0; i--) predicted[i] = (byte) ((raw[i] & 255) - (raw[i - 1] & 255) + 128);
        byte[] interleaved = new byte[predicted.length]; int at = 0;
        for (int i = 0; i < predicted.length; i += 2) interleaved[at++] = predicted[i];
        for (int i = 1; i < predicted.length; i += 2) interleaved[at++] = predicted[i];
        Deflater deflater = new Deflater(); deflater.setInput(interleaved); deflater.finish();
        byte[] compressed = new byte[interleaved.length + 128]; int count = deflater.deflate(compressed); deflater.end();
        return Arrays.copyOf(compressed, count);
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
        check(c.image().getRGB(0, 0) == (c.image().getRGB(0, 0) & 0xff00ffff | ((c.image().getRGB(0, 0) >> 16 & 255) << 16)), "gray decode alpha");
        check(c.image().getRGB(0, 0) == c.image().getRGB(0, 0), "gray stable");
        if (args.length > 0) {
            Path dir = Path.of(args[0]); Files.createDirectories(dir);
            Files.write(dir.resolve("sample.exr"), raw); Files.write(dir.resolve("sample.tx"), zip);
            check(PanoramaImageDecoder.decode(dir.resolve("sample.exr"), 4096, 2048).image().getWidth() == 4, "EXR dispatch");
            check(PanoramaImageDecoder.decode(dir.resolve("sample.tx"), 4096, 2048).image().getWidth() == 4, "TX dispatch");
        }
        System.out.println("PASS: " + checks + " EXR/TX assertions");
    }
}
