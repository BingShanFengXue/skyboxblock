package dev.yg.skyboxblock.custom;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.util.Arrays;

/**
 * Minimal pure-JDK decoder for Radiance RGBE panoramas ({@code .hdr}).
 *
 * <p>Scope is deliberately narrow: the decoder exists so a hand-made HDR panorama can be folded
 * into the same 8-bit sRGB pipeline that the custom skybox folder already uses (the generated pack
 * only ever holds {@code TYPE_INT_ARGB} PNGs). It therefore produces a <em>display-referred</em>
 * image, never a float image:
 *
 * <ol>
 *   <li>RGBE samples are turned into linear radiance with {@code mantissa * 2^(E-136)}; a zero
 *       exponent byte is black by definition and is not scaled.</li>
 *   <li>A single global exposure derived from the image itself is applied, so a dim and a bright
 *       panorama of the same scene land on the same mid-tone:
 *       {@code exposure = 0.45 / max(median(linear luminance), 1e-6)}.</li>
 *   <li>Linear values are compressed with a global Reinhard map
 *       ({@code L / (1 + L)} on the exposed luminance, chromaticity preserved), then encoded with
 *       the sRGB transfer function and rounded to 8 bits per channel.</li>
 * </ol>
 *
 * <p>Everything runs synchronously on the calling thread and is meant for mod start-up only
 * ({@link CustomSkyRegistry#load}); no caches, no threads, no GPU work.
 *
 * <p><b>Supported</b>: {@code #?RADIANCE} and {@code #?RGBE} signatures, {@code FORMAT=32-bit_rle_rgbe},
 * the conventional per-channel scanline RLE ({@code 0x02 0x02 hi lo} followed by runs and literals),
 * flat scanlines written as four bytes per pixel, and any of the eight resolution spellings that put
 * {@code X} first or {@code Y} first with either sign ({@code -Y h +X w}, {@code +X w +Y h}, ...).
 *
 * <p><b>Explicitly unsupported</b> (rejected or decoded as flat data, never silently accepted):
 * <ul>
 *   <li>{@code FORMAT=32-bit_rle_xyze} and every other {@code FORMAT} value — rejected with the
 *       offending value in the message, because XYZE is CIE XYZ, not RGB.</li>
 *   <li>OpenEXR ({@code .exr}) — a different container with no Radiance signature at all.</li>
 *   <li>Legacy old-style RLE, where a repeated pixel is written as {@code (1, 1, 1, count)}. Those
 *       runs are <em>not</em> expanded; a run marker with {@code count > 1} is reported as an error
 *       instead of being silently flattened, so a 1990s-era file cannot masquerade as a valid one.
 *       Files that merely stopped using RLE (plain four-bytes-per-pixel scanlines) are supported.</li>
 * </ul>
 *
 * <p><b>Memory</b>: two passes over the caller's byte array (exposure, then shading), one reusable
 * {@code 4 * width} scanline buffer, one sample array of at most {@code 64 * 64} doubles, and the
 * destination {@code int[]} taken straight out of the {@code TYPE_INT_ARGB} raster. No per-pixel
 * objects, no full-resolution RGBE or float copy.
 *
 * <p><b>Deterministic sampling</b>: luminance is sampled on a fixed grid of at most 64 columns and
 * 64 rows, {@code stride = ceil(size / 64)}, i.e. the pixels whose {@code x % strideX == 0} and
 * {@code y % strideY == 0}; the median of those samples (mean of the two central values for an even
 * count) feeds the exposure. The grid is independent of image content, so the same file always
 * yields the same exposure.
 */
public final class RadianceHdr {

    /** Signature written by every modern Radiance writer. */
    public static final String MAGIC_RADIANCE = "#?RADIANCE";
    /** Older signature, same payload. */
    public static final String MAGIC_RGBE = "#?RGBE";
    /** The only pixel layout this decoder accepts. */
    public static final String FORMAT_RGBE = "32-bit_rle_rgbe";

    /** Rec. 709 luminance weights, applied to linear radiance. */
    private static final double LUMINANCE_R = 0.2126;
    private static final double LUMINANCE_G = 0.7152;
    private static final double LUMINANCE_B = 0.0722;

    /** Exposure contract shared with the R31 asset pipeline. */
    private static final double TARGET_LUMINANCE = 0.45;
    private static final double MIN_MEDIAN_LUMINANCE = 1e-6;

    /** Sampling grid: at most this many columns and rows are inspected. */
    private static final int SAMPLE_GRID = 64;

    private static final int MAX_HEADER_LINES = 128;
    private static final int MAX_HEADER_LINE_BYTES = 512;
    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_RESOLUTION_LINE_BYTES = 128;

    /** Hard ceilings, independent of the caller's own limits; they keep allocation arithmetic sane. */
    private static final int MAX_DIMENSION = 16384;
    private static final long MAX_PIXELS = 16_777_216L;

    /** Radiance only RLE-encodes scanlines this wide; everything else is a flat scanline. */
    private static final int MIN_RLE_WIDTH = 8;
    private static final int MAX_RLE_WIDTH = 32767;

    private static final int RLE_SIGNATURE = 2;
    private static final int RLE_RUN_BIAS = 128;

    /**
     * {@code 2^(E - 136)} for every exponent byte; a table keeps the per-pixel path free of
     * {@code Math.pow}. Static state is immutable, so concurrent readers are safe.
     */
    private static final float[] EXPONENT_SCALE = new float[256];

    static {
        for (int exponent = 0; exponent < 256; exponent++) {
            EXPONENT_SCALE[exponent] = (float) Math.scalb(1.0, exponent - 136);
        }
    }

    private RadianceHdr() {
    }

    /**
     * A decoded panorama plus the numbers that produced it, so callers can log the tone mapping
     * without recomputing anything.
     *
     * @param image              opaque {@code TYPE_INT_ARGB} sRGB image
     * @param exposure           {@code 0.45 / max(medianLuminance, 1e-6)}
     * @param medianLuminance    median linear luminance of the sampling grid, before clamping
     */
    public record Decoded(BufferedImage image, double exposure, double medianLuminance) {
    }

    /** Cheap content sniff, so a caller can decide to route a file here before parsing it. */
    public static boolean looksLikeRadiance(byte[] file) {
        if (file == null) return false;
        int start = skipByteOrderMark(file);
        return matches(file, start, MAGIC_RADIANCE) || matches(file, start, MAGIC_RGBE);
    }

    /**
     * Decodes a complete Radiance file held in memory.
     *
     * @param file      whole file bytes
     * @param maxWidth  caller ceiling for the panorama width (custom skies use 4096)
     * @param maxHeight caller ceiling for the panorama height (custom skies use 2048)
     * @return display-referred sRGB image plus its exposure
     * @throws IOException              on any malformed, truncated, oversized or unsupported input
     * @throws IllegalArgumentException when an argument itself is unusable
     */
    public static Decoded decode(byte[] file, int maxWidth, int maxHeight) throws IOException {
        if (file == null) throw new IllegalArgumentException("HDR 数据为 null");
        if (maxWidth <= 0 || maxHeight <= 0) {
            throw new IllegalArgumentException("HDR 尺寸上限必须为正数：" + maxWidth + "x" + maxHeight);
        }
        Header header = readHeader(file, maxWidth, maxHeight);
        Grid grid = Grid.of(header.width, header.height);
        double[] samples = sampleLuminance(file, header, grid);
        double median = median(samples);
        double exposure = TARGET_LUMINANCE / Math.max(median, MIN_MEDIAN_LUMINANCE);
        BufferedImage image = render(file, header, exposure);
        return new Decoded(image, exposure, median);
    }

    // ── header ──────────────────────────────────────────────────────────────────────────────────

    /** Parsed and validated header: pixel geometry, orientation and where the raster starts. */
    private record Header(int width, int height, boolean flipX, boolean flipY, boolean xFirst, int dataOffset) {
    }

    private static Header readHeader(byte[] file, int maxWidth, int maxHeight) throws IOException {
        Cursor cursor = new Cursor(file, skipByteOrderMark(file));
        String signature = readLine(cursor, 32, "签名行");
        if (!signature.equals(MAGIC_RADIANCE) && !signature.equals(MAGIC_RGBE)) {
            throw new IOException("不是 Radiance HDR：签名必须是 " + MAGIC_RADIANCE + " 或 " + MAGIC_RGBE
                    + "，实际 \"" + printable(signature) + "\"");
        }
        String format = null;
        int headerBytes = 0;
        for (int lineNumber = 1; ; lineNumber++) {
            if (lineNumber > MAX_HEADER_LINES) {
                throw new IOException("HDR 头信息超过 " + MAX_HEADER_LINES + " 行上限，未找到空行");
            }
            String line = readLine(cursor, MAX_HEADER_LINE_BYTES, "头信息");
            headerBytes += line.length() + 1;
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new IOException("HDR 头信息超过 " + MAX_HEADER_BYTES + " 字节上限，未找到空行");
            }
            if (line.isEmpty()) break;
            if (line.charAt(0) == '#') continue;
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IOException("HDR 头信息行非法（需要 KEY=VALUE 或 # 注释）：\"" + printable(line) + "\"");
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (!isPrintableAscii(line)) {
                throw new IOException("HDR 头信息含非 ASCII 字节：\"" + printable(line) + "\"");
            }
            if (key.equalsIgnoreCase("FORMAT")) {
                if (format != null) throw new IOException("HDR 头信息重复的 FORMAT（" + format + " / " + value + "）");
                format = value;
            }
            // EXPOSURE, COLORCORR, PRIMARIES, SOFTWARE, VIEW, ... are informational here: the
            // pipeline derives its own exposure from the decoded radiance.
        }
        if (format == null) {
            throw new IOException("HDR 头信息缺少 FORMAT=" + FORMAT_RGBE);
        }
        if (!format.equalsIgnoreCase(FORMAT_RGBE)) {
            throw new IOException("HDR FORMAT 不受支持：" + printable(format)
                    + "（只支持 " + FORMAT_RGBE + "；XYZE 等其他布局一律拒绝）");
        }
        String resolution = readLine(cursor, MAX_RESOLUTION_LINE_BYTES, "分辨率行");
        return parseResolution(resolution, cursor.pos, maxWidth, maxHeight);
    }

    private static Header parseResolution(String line, int dataOffset, int maxWidth, int maxHeight)
            throws IOException {
        String trimmed = line.trim();
        String[] tokens = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        if (tokens.length != 4) {
            throw new IOException("HDR 分辨率行格式错误：需要形如 -Y 512 +X 1024 的四段，实际 \""
                    + printable(trimmed) + "\"");
        }
        int width = -1;
        int height = -1;
        boolean flipX = false;
        boolean flipY = false;
        for (int index = 0; index < 4; index += 2) {
            String axisToken = tokens[index];
            if (axisToken.isEmpty()) {
                throw new IOException("HDR 分辨率轴标记错误（需要 ±X / ±Y）：\"\"");
            }
            char sign = axisToken.charAt(0);
            if (sign != '+' && sign != '-') {
                throw new IOException("HDR 分辨率轴标记缺少符号：\"" + printable(axisToken) + "\"");
            }
            if (axisToken.length() != 2) {
                throw new IOException("HDR 分辨率轴标记错误（需要 ±X / ±Y）：\"" + printable(axisToken) + "\"");
            }
            char axis = Character.toUpperCase(axisToken.charAt(1));
            if (axis != 'X' && axis != 'Y') {
                throw new IOException("HDR 分辨率轴标记未知：\"" + printable(axisToken) + "\"");
            }
            int value = parseDimension(tokens[index + 1], axis);
            if (axis == 'X') {
                if (width >= 0) throw new IOException("HDR 分辨率轴重复：X 出现两次（\"" + printable(trimmed) + "\"）");
                width = value;
                // '+X' means left-to-right; '-X' means the raster runs right-to-left.
                flipX = sign == '-';
            } else {
                if (height >= 0) throw new IOException("HDR 分辨率轴重复：Y 出现两次（\"" + printable(trimmed) + "\"）");
                height = value;
                // '-Y' means the first scanline is the top row; '+Y' means bottom-up.
                flipY = sign == '+';
            }
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IOException("HDR 图像 " + width + "x" + height + " 超过硬上限 " + MAX_DIMENSION + " 边长");
        }
        long pixels = (long) width * (long) height;
        if (pixels > MAX_PIXELS) {
            throw new IOException("HDR 图像 " + width + "x" + height + " 超过硬上限 " + MAX_PIXELS + " 像素");
        }
        if (width > maxWidth || height > maxHeight) {
            throw new IOException("HDR 图像 " + width + "x" + height + " 超过调用方上限 "
                    + maxWidth + "x" + maxHeight);
        }
        boolean xFirst = Character.toUpperCase(tokens[0].charAt(1)) == 'X';
        return new Header(width, height, flipX, flipY, xFirst, dataOffset);
    }

    private static int parseDimension(String token, char axis) throws IOException {
        if (token.isEmpty() || token.length() > 9) {
            throw new IOException("HDR " + axis + " 轴长度非法：\"" + printable(token) + "\"");
        }
        long value = 0;
        for (int index = 0; index < token.length(); index++) {
            char digit = token.charAt(index);
            if (digit < '0' || digit > '9') {
                throw new IOException("HDR " + axis + " 轴长度不是十进制数字：\"" + printable(token) + "\"");
            }
            value = value * 10 + (digit - '0');
            if (value > MAX_DIMENSION) {
                throw new IOException("HDR " + axis + " 轴长度 " + token + " 超过硬上限 " + MAX_DIMENSION);
            }
        }
        if (value <= 0) throw new IOException("HDR " + axis + " 轴长度必须为正：\"" + printable(token) + "\"");
        return (int) value;
    }

    // ── exposure ────────────────────────────────────────────────────────────────────────────────

    /** Fixed sampling grid; at most 64 columns by 64 rows, independent of file content. */
    private record Grid(int strideX, int[] columns, int strideY) {

        static Grid of(int width, int height) {
            int strideX = Math.max(1, (width + SAMPLE_GRID - 1) / SAMPLE_GRID);
            int strideY = Math.max(1, (height + SAMPLE_GRID - 1) / SAMPLE_GRID);
            int columns = (width - 1) / strideX + 1;
            int[] xs = new int[columns];
            for (int index = 0; index < columns; index++) xs[index] = index * strideX;
            return new Grid(strideX, xs, strideY);
        }
    }

    /** First pass: decode every scanline, keep luminance only where the grid asks for it. */
    private static double[] sampleLuminance(byte[] file, Header header, Grid grid) throws IOException {
        double[] samples = new double[grid.columns().length * (((header.height() - 1) / grid.strideY()) + 1)];
        Scanlines scanlines = new Scanlines(file, header);
        int at = 0;
        int lines = header.xFirst() ? header.width() : header.height();
        int length = header.xFirst() ? header.height() : header.width();
        for (int line = 0; line < lines; line++) {
            scanlines.readRow(line);
            for (int pixel = 0; pixel < length; pixel++) {
                int x = header.xFirst() ? line : pixel;
                int y = header.xFirst() ? pixel : line;
                if (header.flipX()) x = header.width() - 1 - x;
                if (header.flipY()) y = header.height() - 1 - y;
                if (x % grid.strideX() == 0 && y % grid.strideY() == 0)
                    samples[at++] = luminance(scanlines.row, pixel * 4);
            }
        }
        return samples;
    }

    /** Linear luminance of one RGBE sample; {@code E == 0} is black by definition. */
    private static double luminance(byte[] row, int offset) {
        int exponent = row[offset + 3] & 0xFF;
        if (exponent == 0) return 0.0;
        double scale = EXPONENT_SCALE[exponent];
        double r = (row[offset] & 0xFF) * scale;
        double g = (row[offset + 1] & 0xFF) * scale;
        double b = (row[offset + 2] & 0xFF) * scale;
        return LUMINANCE_R * r + LUMINANCE_G * g + LUMINANCE_B * b;
    }

    /** Median of the samples: the single central value, or the mean of the two central values. */
    private static double median(double[] samples) {
        Arrays.sort(samples);
        int count = samples.length;
        if ((count & 1) == 1) return samples[count >> 1];
        return 0.5 * (samples[(count >> 1) - 1] + samples[count >> 1]);
    }

    // ── shading ─────────────────────────────────────────────────────────────────────────────────

    /** Second pass: decode again, tone-map and write the interlaced ARGB raster in place. */
    private static BufferedImage render(byte[] file, Header header, double exposure) throws IOException {
        int width = header.width();
        int height = header.height();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = directPixels(image);
        boolean shared = pixels != null;
        if (!shared) pixels = new int[width * height];
        Scanlines scanlines = new Scanlines(file, header);
        int lines = header.xFirst() ? width : height;
        int length = header.xFirst() ? height : width;
        for (int line = 0; line < lines; line++) {
            scanlines.readRow(line);
            for (int pixel = 0; pixel < length; pixel++) {
                int x = header.xFirst() ? line : pixel;
                int y = header.xFirst() ? pixel : line;
                if (header.flipX()) x = width - 1 - x;
                if (header.flipY()) y = height - 1 - y;
                pixels[y * width + x] = shade(scanlines.row, pixel * 4, exposure);
            }
        }
        if (!shared) image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    /** The raster's own storage when the colour model allows it, so no second int[] is allocated. */
    private static int[] directPixels(BufferedImage image) {
        if (image.getRaster().getDataBuffer() instanceof DataBufferInt buffer && buffer.getNumBanks() == 1) {
            return buffer.getData();
        }
        return null;
    }

    /**
     * Exposure-normalized Reinhard with the chromaticity of the source pixel preserved:
     * {@code out = rgb * exposure / (1 + L * exposure)} where {@code L} is the pixel's luminance.
     */
    private static int shade(byte[] row, int offset, double exposure) {
        int exponent = row[offset + 3] & 0xFF;
        if (exponent == 0) return 0xFF000000;
        double scale = EXPONENT_SCALE[exponent];
        double r = (row[offset] & 0xFF) * scale;
        double g = (row[offset + 1] & 0xFF) * scale;
        double b = (row[offset + 2] & 0xFF) * scale;
        double luma = LUMINANCE_R * r + LUMINANCE_G * g + LUMINANCE_B * b;
        if (!(luma > 0.0)) return 0xFF000000;
        double exposed = luma * exposure;
        double gain = exposure / (1.0 + exposed);
        return 0xFF000000 | (srgb8(r * gain) << 16) | (srgb8(g * gain) << 8) | srgb8(b * gain);
    }

    /** Linear light to 8-bit sRGB, using the standard piecewise transfer curve. */
    private static int srgb8(double linear) {
        if (!(linear > 0.0)) return 0;
        double encoded = linear <= 0.0031308
                ? 12.92 * linear
                : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
        int value = (int) Math.round(encoded * 255.0);
        if (value < 0) return 0;
        return Math.min(value, 255);
    }

    // ── scanline decoding ───────────────────────────────────────────────────────────────────────

    /**
     * Sequential scanline reader. One {@code 4 * width} row buffer is reused for every row; the
     * decoded bytes are the raw channel-interleaved {@code R G B E} samples.
     */
    private static final class Scanlines {
        private final Cursor cursor;
        private final int width;
        private final byte[] row;

        Scanlines(byte[] file, Header header) {
            this.cursor = new Cursor(file, header.dataOffset());
            this.width = header.xFirst() ? header.height() : header.width();
            this.row = new byte[this.width * 4];
        }

        void readRow(int rowIndex) throws IOException {
            int base = cursor.pos;
            int available = cursor.data.length - base;
            // Radiance only RLE-encodes scanlines in 8..32767; narrower or wider rows are flat even
            // when their first pixel happens to look like an RLE signature.
            boolean rleCandidate = width >= MIN_RLE_WIDTH && width <= MAX_RLE_WIDTH;
            if (rleCandidate
                    && available >= 4
                    && (cursor.data[base] & 0xFF) == RLE_SIGNATURE
                    && (cursor.data[base + 1] & 0xFF) == RLE_SIGNATURE
                    && ((cursor.data[base + 2] & 0x80) == 0)) {
                readRleRow(rowIndex);
            } else {
                readFlatRow(rowIndex, available);
            }
        }

        /** Conventional per-channel scanline RLE: {@code 0x02 0x02 hi lo}, then four channels. */
        private void readRleRow(int rowIndex) throws IOException {
            cursor.require(4, rowIndex);
            int high = cursor.data[cursor.pos + 2] & 0xFF;
            int low = cursor.data[cursor.pos + 3] & 0xFF;
            int declared = (high << 8) | low;
            cursor.pos += 4;
            if (declared != width) {
                throw new IOException("HDR RLE 扫描线声明宽度 " + declared + " 与图像宽度 " + width
                        + " 不符（扫描线 " + rowIndex + "）");
            }
            for (int channel = 0; channel < 4; channel++) {
                decodeChannel(rowIndex, channel);
            }
        }

        private void decodeChannel(int rowIndex, int channel) throws IOException {
            int column = 0;
            while (column < width) {
                cursor.require(1, rowIndex);
                int count = cursor.data[cursor.pos++] & 0xFF;
                if (count > RLE_RUN_BIAS) {
                    int run = count - RLE_RUN_BIAS;
                    cursor.require(1, rowIndex);
                    byte value = cursor.data[cursor.pos++];
                    if (column + run > width) {
                        throw new IOException("HDR RLE 游程 " + run + " 超出扫描线剩余 " + (width - column)
                                + " 像素（扫描线 " + rowIndex + "，通道 " + channel + "）");
                    }
                    for (int repeat = 0; repeat < run; repeat++) {
                        row[(column++) * 4 + channel] = value;
                    }
                } else {
                    if (count == 0) {
                        throw new IOException("HDR RLE 字面量计数为 0（扫描线 " + rowIndex
                                + "，通道 " + channel + "）");
                    }
                    if (column + count > width) {
                        throw new IOException("HDR RLE 字面量 " + count + " 超出扫描线剩余 " + (width - column)
                                + " 像素（扫描线 " + rowIndex + "，通道 " + channel + "）");
                    }
                    cursor.require(count, rowIndex);
                    for (int repeat = 0; repeat < count; repeat++) {
                        row[(column++) * 4 + channel] = cursor.data[cursor.pos++];
                    }
                }
            }
        }

        /** Flat scanline: four bytes per pixel, no compression. */
        private void readFlatRow(int rowIndex, int available) throws IOException {
            int needed = width * 4;
            if (available < needed) {
                throw new IOException("HDR 数据在扫描线 " + rowIndex + " 处截断：需要 " + needed
                        + " 字节，只剩 " + available);
            }
            System.arraycopy(cursor.data, cursor.pos, row, 0, needed);
            cursor.pos += needed;
            for (int column = 0; column < width; column++) {
                int offset = column * 4;
                boolean repeatMarker = row[offset] == 1 && row[offset + 1] == 1 && row[offset + 2] == 1;
                if (repeatMarker && (row[offset + 3] & 0xFF) > 1) {
                    throw new IOException("HDR 旧式 RLE 不受支持（扫描线 " + rowIndex + "，像素 " + column
                            + " 的 (1,1,1," + (row[offset + 3] & 0xFF) + ") 重复标记）");
                }
            }
        }
    }

    // ── byte plumbing ───────────────────────────────────────────────────────────────────────────

    /** Bounds-checked read head over the caller's array; nothing is copied. */
    private static final class Cursor {
        final byte[] data;
        int pos;

        Cursor(byte[] data, int pos) {
            this.data = data;
            this.pos = pos;
        }

        void require(int count, int rowIndex) throws IOException {
            if (data.length - pos < count) {
                throw new IOException("HDR 数据在扫描线 " + rowIndex + " 处截断：需要 " + count
                        + " 字节，只剩 " + Math.max(0, data.length - pos));
            }
        }
    }

    /** Reads one line without its terminator and without trailing whitespace. */
    private static String readLine(Cursor cursor, int maxBytes, String what) throws IOException {
        StringBuilder text = new StringBuilder(Math.min(maxBytes, 64));
        while (true) {
            if (cursor.pos >= cursor.data.length) {
                throw new IOException("HDR " + what + " 不完整：数据提前结束");
            }
            int value = cursor.data[cursor.pos++] & 0xFF;
            if (value == '\n') break;
            if (text.length() >= maxBytes) {
                throw new IOException("HDR " + what + " 超过 " + maxBytes + " 字节上限");
            }
            text.append((char) value);
        }
        int end = text.length();
        while (end > 0) {
            char tail = text.charAt(end - 1);
            if (tail != '\r' && tail != ' ' && tail != '\t') break;
            end--;
        }
        return text.substring(0, end);
    }

    private static int skipByteOrderMark(byte[] file) {
        if (file.length >= 3 && (file[0] & 0xFF) == 0xEF && (file[1] & 0xFF) == 0xBB
                && (file[2] & 0xFF) == 0xBF) {
            return 3;
        }
        return 0;
    }

    private static boolean matches(byte[] file, int start, String magic) {
        if (file.length - start < magic.length()) return false;
        for (int index = 0; index < magic.length(); index++) {
            if ((file[start + index] & 0xFF) != magic.charAt(index)) return false;
        }
        return true;
    }

    private static boolean isPrintableAscii(String line) {
        for (int index = 0; index < line.length(); index++) {
            char value = line.charAt(index);
            if (value < 0x20 || value > 0x7E) return false;
        }
        return true;
    }

    /** Keeps hostile header text from injecting control characters into log output. */
    private static String printable(String text) {
        StringBuilder safe = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            safe.append(value >= 0x20 && value <= 0x7E ? value : '?');
        }
        return safe.toString();
    }
}
