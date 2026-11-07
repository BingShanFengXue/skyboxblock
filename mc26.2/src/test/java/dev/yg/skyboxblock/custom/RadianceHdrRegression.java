package dev.yg.skyboxblock.custom;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * RadianceHdr 的回归：纯 JDK、无 Minecraft、无 Gradle，{@code java dev.yg.skyboxblock.custom.RadianceHdrRegression}
 * 直接运行（可带一个报告文件路径参数）。
 *
 * <p>覆盖四类事实：</p>
 * <ol>
 *   <li><b>合法输入</b>：手写 raw RGBE 扫描线、手写 RLE 字节流（游程 + 字面量）、程序化 RLE 流，
 *       以及两套编码同一画面的逐像素等价、四种轴顺序/符号的组合与镜像关系。</li>
 *   <li><b>色调映射</b>：由参考实现（独立按文档公式算出）固化的 sRGB 期望值，
 *       含曝光归一化不变性、黑色 E==0、色度保持与饱和钳制。</li>
 *   <li><b>非法输入</b>：签名 / FORMAT / 头信息 / 分辨率行 / 尺寸上限 / 截断 / RLE 结构错误，
 *       每条都必须抛出带可读原因的 {@link IOException}，不得静默接受。</li>
 *   <li><b>退化输入</b>：null、空文件、只有签名、调用方上限为 0。</li>
 * </ol>
 *
 * <p>期望值来源：{@code python} 复算 {@code exposure = 0.45 / median(L)}、
 * {@code out = c * exposure / (1 + L * exposure)}、sRGB 分段传递函数后取 8bit 四舍五入，
 * 与 {@link RadianceHdr} 无共享代码。</p>
 */
public final class RadianceHdrRegression {

    /** 与生产调用方（CustomSkyRegistry）一致的尺寸上限。 */
    private static final int MAX_W = 4096;
    private static final int MAX_H = 2048;

    private static int checks;

    // 参考期望值（python 独立复算，见类注释）
    private static final int GRAY_151 = 0xFF979797;
    private static final int WHITE_255 = 0xFFFFFFFF;
    private static final int WHITE_254 = 0xFFFEFEFE;

    // 主测试画面：16x4，底色 + 两个通道标记
    private static final int BASE = rgbe(64, 128, 192, 136);
    private static final int RED_MARKER = rgbe(255, 0, 0, 141);
    private static final int BLUE_MARKER = rgbe(0, 0, 255, 141);
    private static final int BASE_SRGB = 0xFF729CBC;   // (114,156,188)
    private static final int RED_SRGB = 0xFFFF0000;
    private static final int BLUE_SRGB = 0xFF0000FF;
    private static final double BASE_EXPOSURE = 0.003781055065605507;
    private static final double BASE_MEDIAN = 119.0144;

    private RadianceHdrRegression() {
    }

    // ── 断言设施 ─────────────────────────────────────────────────────────────

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) {
            System.out.println("FAIL: " + name);
            throw new AssertionError(name);
        }
    }

    private static void eq(Object expected, Object actual, String name) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        check(ok, ok ? name : name + " → 期望[" + expected + "] 实际[" + actual + "]");
    }

    private static String hex(int argb) {
        return String.format(Locale.ROOT, "0x%08X", argb);
    }

    private static void eqPixel(BufferedImage image, int x, int y, int expected, String name) {
        int actual = image.getRGB(x, y);
        check(actual == expected,
                name + " → (" + x + "," + y + ") 期望[" + hex(expected) + "] 实际[" + hex(actual) + "]");
    }

    private static void near(double expected, double actual, double tolerance, String name) {
        boolean ok = Math.abs(expected - actual) <= tolerance;
        check(ok, ok ? name : name + " → 期望[" + expected + "] 实际[" + actual + "] 容差[" + tolerance + "]");
    }

    private static void expectError(String name, byte[] data, int maxWidth, int maxHeight, String needle) {
        try {
            RadianceHdr.Decoded decoded = RadianceHdr.decode(data, maxWidth, maxHeight);
            check(false, name + " → 期望 IOException（含[" + needle + "]），实际解码成功 "
                    + decoded.image().getWidth() + "x" + decoded.image().getHeight());
        } catch (IOException error) {
            String message = String.valueOf(error.getMessage());
            check(message.contains(needle),
                    name + " → 期望错误含[" + needle + "]，实际[" + message + "]");
        }
    }

    /** 让实现自己抛出的任何“意外”异常也带上用例名。 */
    private static void mustReject(String name, String needle, ThrowingSupplier<byte[]> data) {
        byte[] bytes;
        try {
            bytes = data.get();
        } catch (IOException error) {
            throw new AssertionError(name + " → 构造样本失败：" + error, error);
        }
        expectError(name, bytes, MAX_W, MAX_H, needle);
    }

    private interface ThrowingSupplier<T> {
        T get() throws IOException;
    }

    // ── RGBE / 采样设施 ──────────────────────────────────────────────────────

    /** 打包一个 RGBE 样本。 */
    private static int rgbe(int r, int g, int b, int e) {
        return ((r & 0xFF) << 24) | ((g & 0xFF) << 16) | ((b & 0xFF) << 8) | (e & 0xFF);
    }

    private static int[][] uniform(int width, int height, int sample) {
        int[][] rows = new int[height][width];
        for (int[] row : rows) Arrays.fill(row, sample);
        return rows;
    }

    /** 主测试画面：底色的 2:1 等距柱状图，第 0 行左右两端各放一个通道标记。 */
    private static int[][] markerImage() {
        int[][] rows = uniform(16, 4, BASE);
        rows[0][0] = RED_MARKER;
        rows[0][15] = BLUE_MARKER;
        return rows;
    }

    /** raw 编码：每个像素 4 字节，无压缩。 */
    private static byte[] flat(int[][] rows) {
        int height = rows.length;
        int width = rows[0].length;
        byte[] out = new byte[width * height * 4];
        int at = 0;
        for (int y = 0; y < height; y++) {
            check(rows[y].length == width, "flat 输入行宽一致（第 " + y + " 行）");
            for (int x = 0; x < width; x++) {
                int sample = rows[y][x];
                out[at++] = (byte) (sample >>> 24);
                out[at++] = (byte) (sample >>> 16);
                out[at++] = (byte) (sample >>> 8);
                out[at++] = (byte) sample;
            }
        }
        return out;
    }

    /** 与 Radiance 规范一致的逐通道 RLE 编码：{@code 0x02 0x02 hi lo} + 游程/字面量。 */
    private static byte[] rle(int[][] rows) {
        int height = rows.length;
        int width = rows[0].length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int y = 0; y < height; y++) {
            out.write(2);
            out.write(2);
            out.write((width >> 8) & 0xFF);
            out.write(width & 0xFF);
            for (int channel = 0; channel < 4; channel++) {
                int x = 0;
                while (x < width) {
                    int value = channelOf(rows[y][x], channel);
                    int run = 1;
                    while (x + run < width && channelOf(rows[y][x + run], channel) == value && run < 127) run++;
                    if (run >= 3) {
                        out.write(128 + run);
                        out.write(value);
                        x += run;
                        continue;
                    }
                    int literalStart = x;
                    int literalCount = 0;
                    while (x < width && literalCount < 128) {
                        int head = channelOf(rows[y][x], channel);
                        int repeats = 1;
                        while (x + repeats < width && channelOf(rows[y][x + repeats], channel) == head
                                && repeats < 3) {
                            repeats++;
                        }
                        if (repeats >= 3) break;
                        x++;
                        literalCount++;
                    }
                    if (literalCount == 0) {
                        // 落回游程分支，避免死循环
                        continue;
                    }
                    out.write(literalCount);
                    for (int index = literalStart; index < literalStart + literalCount; index++) {
                        out.write(channelOf(rows[y][index], channel));
                    }
                }
            }
        }
        return out.toByteArray();
    }

    private static int channelOf(int sample, int channel) {
        return (sample >>> (24 - 8 * channel)) & 0xFF;
    }

    private static byte[] header(String resolution, byte[] raster) {
        return header("#?RADIANCE", null, resolution, raster);
    }

    private static byte[] header(String magic, String extraHeader, String resolution, byte[] raster) {
        StringBuilder text = new StringBuilder(magic).append('\n');
        text.append("FORMAT=32-bit_rle_rgbe\n");
        if (extraHeader != null && !extraHeader.isEmpty()) {
            text.append(extraHeader);
            if (extraHeader.charAt(extraHeader.length() - 1) != '\n') text.append('\n');
        }
        text.append('\n').append(resolution).append('\n');
        return concat(text.toString().getBytes(StandardCharsets.ISO_8859_1), raster);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private static byte[] slice(byte[] data, int length) {
        return Arrays.copyOf(data, Math.min(length, data.length));
    }

    private static RadianceHdr.Decoded decode(byte[] data) throws IOException {
        return RadianceHdr.decode(data, MAX_W, MAX_H);
    }

    // ── 用例正文 ─────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        rawScanlines();
        rleScanlines();
        rleStructure();
        rleMatchesFlat();
        orientation();
        exposureNormalisation();
        knownReinhardValues();
        samplerBound();
        headerVariants();
        resolutionVariants();
        capacityGuards();
        truncatedStreams();
        invalidRuns();
        legacyAndUnsupported();
        degenerateInputs();

        String report = "PASS: " + checks + " assertions\n";
        System.out.print(report);
        if (args.length > 0) {
            Path path = Path.of(args[0]);
            if (path.toAbsolutePath().getParent() != null) {
                Files.createDirectories(path.toAbsolutePath().getParent());
            }
            Files.writeString(path, report, StandardCharsets.UTF_8);
        }
    }

    /** raw 扫描线：4x2，全是同一个灰，期望值来自参考实现。 */
    private static void rawScanlines() throws IOException {
        int[][] rows = uniform(4, 2, rgbe(128, 128, 128, 135));
        RadianceHdr.Decoded decoded = decode(header("-Y 2 +X 4", flat(rows)));
        BufferedImage image = decoded.image();
        eq(4, image.getWidth(), "raw 宽度");
        eq(2, image.getHeight(), "raw 高度");
        eq(BufferedImage.TYPE_INT_ARGB, image.getType(), "raw 输出必须是 TYPE_INT_ARGB");
        near(0.00703125, decoded.exposure(), 1e-15, "raw 曝光 0.45/64");
        near(64.0, decoded.medianLuminance(), 1e-12, "raw 中位亮度 64");
        for (int y = 0; y < 2; y++)
            for (int x = 0; x < 4; x++) eqPixel(image, x, y, GRAY_151, "raw 均匀灰 (151,151,151)");
        check((image.getRGB(0, 0) >>> 24) == 0xFF, "raw 输出不透明");

        // 宽度 >= 8 但首像素不是 0x02 0x02，也必须走 raw 分支
        byte[] wide = flat(uniform(8, 1, rgbe(32, 32, 32, 130)));
        RadianceHdr.Decoded wideDecoded = decode(header("-Y 1 +X 8", wide));
        eq(8, wideDecoded.image().getWidth(), "raw 8 宽扫描线");
        eqPixel(wideDecoded.image(), 7, 0, GRAY_151, "raw 8 宽像素值");

        // 宽度 < 8 时，即使首像素碰巧是 (2,2,0,n) 也不能当成 RLE 头
        int[][] narrow = {{rgbe(2, 2, 0, 3), rgbe(9, 9, 9, 128), rgbe(1, 1, 1, 1), rgbe(7, 7, 7, 129)}};
        RadianceHdr.Decoded narrowDecoded = decode(header("-Y 1 +X 4", flat(narrow)));
        eq(4, narrowDecoded.image().getWidth(), "4 宽首像素像 RLE 头时仍按 raw 解码");
    }

    /** 程序化 RLE：16x4 主画面。 */
    private static void rleScanlines() throws IOException {
        int[][] rows = markerImage();
        RadianceHdr.Decoded decoded = decode(header("-Y 4 +X 16", rle(rows)));
        BufferedImage image = decoded.image();
        eq(16, image.getWidth(), "RLE 宽度");
        eq(4, image.getHeight(), "RLE 高度");
        near(BASE_MEDIAN, decoded.medianLuminance(), 1e-12, "RLE 中位亮度");
        near(BASE_EXPOSURE, decoded.exposure(), 1e-15, "RLE 曝光");
        eqPixel(image, 0, 0, RED_SRGB, "RLE 红色标记");
        eqPixel(image, 15, 0, BLUE_SRGB, "RLE 蓝色标记");
        for (int x = 1; x < 15; x++) eqPixel(image, x, 0, BASE_SRGB, "RLE 底色（第 0 行）");
        for (int y = 1; y < 4; y++)
            for (int x = 0; x < 16; x++) eqPixel(image, x, y, BASE_SRGB, "RLE 底色（其余行）");

        // 同一份字节解两次必须一模一样（无隐藏状态、无随机采样）
        RadianceHdr.Decoded again = decode(header("-Y 4 +X 16", rle(rows)));
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 16; x++) {
                check(again.image().getRGB(x, y) == image.getRGB(x, y), "RLE 重复解码结果一致");
            }
        near(decoded.exposure(), again.exposure(), 0.0, "RLE 重复解码曝光一致");
    }

    /** 手写 RLE 字节流：验证 0x02 0x02 hi lo、游程、字面量的字节级语义。 */
    private static void rleStructure() throws IOException {
        // 8x1，像素全是 (10,10,10,128)：每通道都是一个长度 8 的游程
        int[][] rows = uniform(8, 1, rgbe(10, 10, 10, 128));
        byte[] encoded = rle(rows);
        byte[] expected = {
                0x02, 0x02, 0x00, 0x08,
                (byte) 0x88, 0x0A, (byte) 0x88, 0x0A,
                (byte) 0x88, 0x0A, (byte) 0x88, (byte) 0x80
        };
        check(Arrays.equals(expected, encoded),
                "RLE 编码器字节结构 → 期望[" + toHex(expected) + "] 实际[" + toHex(encoded) + "]");
        // 同一串字节，手写（不经编码器）也要解出同样的 8 个像素
        RadianceHdr.Decoded decoded = decode(header("-Y 1 +X 8", expected));
        int[] flatColors = decodedFlat(rows);
        for (int x = 0; x < 8; x++) eqPixel(decoded.image(), x, 0, flatColors[x], "手写 RLE 游程");

        // 16x1 混合：通道 0 是「游程 5 + 字面量 6 + 游程 5」，通道 1 全字面量，通道 2 两段游程，通道 3 单游程
        int[][] mixed = new int[1][16];
        int[] channel0 = {0x40, 0x40, 0x40, 0x40, 0x40, 0x50, 0x51, 0x52, 0x53, 0x54, 0x55,
                0x60, 0x60, 0x60, 0x60, 0x60};
        for (int x = 0; x < 16; x++) {
            mixed[0][x] = rgbe(channel0[x], 0x11 * (x + 1) & 0xFF, x < 8 ? 0x20 : 0x30, 0x86);
        }
        byte[] handWritten = {
                0x02, 0x02, 0x00, 0x10,
                (byte) 0x85, 0x40, 0x06, 0x50, 0x51, 0x52, 0x53, 0x54, 0x55, (byte) 0x85, 0x60,
                0x10, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88, (byte) 0x99, (byte) 0xAA, (byte) 0xBB,
                (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF, 0x10,
                (byte) 0x88, 0x20, (byte) 0x88, 0x30,
                (byte) 0x90, (byte) 0x86
        };
        int[] repaired = new int[16];
        for (int x = 0; x < 16; x++) {
            repaired[x] = rgbe(channel0[x], 0x11 * (x + 1) & 0xFF, x < 8 ? 0x20 : 0x30, 0x86);
        }
        byte[] withRuns = rle(new int[][]{repaired});
        check(Arrays.equals(handWritten, withRuns),
                "混合行编码器字节结构 → 期望[" + toHex(handWritten) + "] 实际[" + toHex(withRuns) + "]");
        RadianceHdr.Decoded handDecoded = decode(header("-Y 1 +X 16", handWritten));
        int[] handColors = decodedFlat(new int[][]{repaired});
        for (int x = 0; x < 16; x++) {
            eqPixel(handDecoded.image(), x, 0, handColors[x], "手写 RLE 游程+字面量混合（像素 " + x + "）");
        }
    }

    /** RLE 与 raw 表示同一画面时必须逐像素一致，曝光也一致。 */
    private static void rleMatchesFlat() throws IOException {
        int[][] rows = markerImage();
        RadianceHdr.Decoded fromRle = decode(header("-Y 4 +X 16", rle(rows)));
        RadianceHdr.Decoded fromFlat = decode(header("-Y 4 +X 16", flat(rows)));
        near(fromFlat.exposure(), fromRle.exposure(), 0.0, "RLE/raw 曝光一致");
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 16; x++) {
                check(fromFlat.image().getRGB(x, y) == fromRle.image().getRGB(x, y),
                        "RLE/raw 像素一致 (" + x + "," + y + ")");
            }

        // 大一点的程序化画面（含渐变，游程与字面量都会出现）
        int width = 512;
        int height = 256;
        int[][] gradient = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = x * 255 / (width - 1);
                int g = y * 200 / (height - 1);
                int b = ((x / 32) + (y / 32)) % 2 == 0 ? 0x30 : 0xC0;
                gradient[y][x] = rgbe(r, g, b, 129);
            }
        }
        RadianceHdr.Decoded bigRle = decode(header("-Y 256 +X 512", rle(gradient)));
        RadianceHdr.Decoded bigFlat = decode(header("-Y 256 +X 512", flat(gradient)));
        eq(width, bigRle.image().getWidth(), "512x256 RLE 宽度");
        eq(height, bigRle.image().getHeight(), "512x256 RLE 高度");
        near(bigFlat.exposure(), bigRle.exposure(), 0.0, "512x256 RLE/raw 曝光一致");
        for (int y = 0; y < height; y += 17) {
            for (int x = 0; x < width; x += 13) {
                check(bigRle.image().getRGB(x, y) == bigFlat.image().getRGB(x, y),
                        "512x256 RLE/raw 采样点一致 (" + x + "," + y + ")");
            }
        }
    }

    /** 轴顺序（Y 先 / X 先）与符号（+/-）的组合必须产生正确的镜像关系。 */
    private static void orientation() throws IOException {
        byte[] raster = rle(markerImage());
        RadianceHdr.Decoded standard = decode(header("-Y 4 +X 16", raster));
        RadianceHdr.Decoded bottomUp = decode(header("+Y 4 +X 16", raster));
        RadianceHdr.Decoded rightToLeft = decode(header("-Y 4 -X 16", raster));
        RadianceHdr.Decoded both = decode(header("+Y 4 -X 16", raster));

        eq(0xFFFF0000, standard.image().getRGB(0, 0), "-Y +X：左上角是红色标记");
        eq(0xFF0000FF, standard.image().getRGB(15, 0), "-Y +X：右上角是蓝色标记");

        // +Y：首行落在底部
        eqPixel(bottomUp.image(), 0, 3, RED_SRGB, "+Y：红色标记在最下行左侧");
        eqPixel(bottomUp.image(), 15, 3, BLUE_SRGB, "+Y：蓝色标记在最下行右侧");
        eqPixel(bottomUp.image(), 0, 0, BASE_SRGB, "+Y：顶行是底色");

        // -X：首列落在右侧
        eqPixel(rightToLeft.image(), 15, 0, RED_SRGB, "-X：红色标记在最上行右侧");
        eqPixel(rightToLeft.image(), 0, 0, BLUE_SRGB, "-X：蓝色标记在最上行左侧");

        eqPixel(both.image(), 15, 3, RED_SRGB, "+Y -X：红色标记在右下角");
        eqPixel(both.image(), 0, 3, BLUE_SRGB, "+Y -X：蓝色标记在左下角");

        // 四张图必须正好互为镜像
        assertMirror(standard.image(), bottomUp.image(), "vertical", "+Y 是 -Y 的垂直镜像");
        assertMirror(standard.image(), rightToLeft.image(), "horizontal", "-X 是 +X 的水平镜像");
        assertMirror(rightToLeft.image(), both.image(), "vertical", "+Y -X 是 -Y -X 的垂直镜像");

        // 曝光与方向无关（采样按文件顺序，同一串字节只有一种采样结果）
        near(standard.exposure(), bottomUp.exposure(), 0.0, "方向不影响曝光");
        near(standard.exposure(), both.exposure(), 0.0, "双翻转不影响曝光");

        // X-first stores columns, not rows: transpose the actual payload as well as the header.
        int[][] source = markerImage();
        int[][] columns = new int[16][4];
        for (int x = 0; x < 16; x++) for (int y = 0; y < 4; y++) columns[x][y] = source[y][x];
        byte[] columnRaster = flat(columns);
        RadianceHdr.Decoded xFirst = decode(header("+X 16 -Y 4", columnRaster));
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 16; x++) {
                check(xFirst.image().getRGB(x, y) == standard.image().getRGB(x, y), "X 轴先写与 Y 轴先写等价");
            }
        RadianceHdr.Decoded xFirstFlipped = decode(header("+X 16 +Y 4", columnRaster));
        assertMirror(standard.image(), xFirstFlipped.image(), "vertical", "+X 16 +Y 4 等于 +Y 4 +X 16");

        // 轴标记大小写不敏感，多空格也接受
        RadianceHdr.Decoded lower = decode(header("-y 4   +x 16", raster));
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 16; x++) {
                check(lower.image().getRGB(x, y) == standard.image().getRGB(x, y), "分辨率行大小写与空白容错");
            }
    }

    /** 曝光归一化：同一画面整体乘以 4 个 stop，输出应完全相同。 */
    private static void exposureNormalisation() throws IOException {
        RadianceHdr.Decoded dim = decode(header("-Y 2 +X 4", flat(uniform(4, 2, rgbe(128, 128, 128, 135)))));
        RadianceHdr.Decoded bright = decode(header("-Y 2 +X 4", flat(uniform(4, 2, rgbe(128, 128, 128, 137)))));
        near(0.00703125, dim.exposure(), 1e-15, "暗图曝光");
        near(0.0017578125, bright.exposure(), 1e-15, "亮图曝光（亮度 x4，曝光 /4）");
        eqPixel(bright.image(), 3, 1, dim.image().getRGB(0, 0), "4 个 stop 的差异被曝光归一化吸收");

        near(64.0, dim.medianLuminance(), 1e-12, "暗图中位亮度");
        near(256.0, bright.medianLuminance(), 1e-12, "亮图中位亮度");
    }

    /** 已知 Reinhard 取值：暗部、高光、色度保持、饱和钳制。 */
    private static void knownReinhardValues() throws IOException {
        // 6 个暗像素 + 2 个高光像素：中位亮度取暗部 4.0，曝光 0.1125
        int[][] rows = new int[2][4];
        Arrays.fill(rows[0], rgbe(64, 64, 64, 132));
        rows[0][2] = rgbe(128, 128, 128, 140);
        rows[0][3] = rgbe(128, 128, 128, 140);
        Arrays.fill(rows[1], rgbe(64, 64, 64, 132));
        RadianceHdr.Decoded decoded = decode(header("-Y 2 +X 4", flat(rows)));
        near(4.0, decoded.medianLuminance(), 1e-12, "暗部中位亮度");
        near(0.1125, decoded.exposure(), 1e-15, "暗部曝光 0.45/4");
        eqPixel(decoded.image(), 0, 0, GRAY_151, "Reinhard 暗部 (151,151,151)");
        eqPixel(decoded.image(), 1, 1, GRAY_151, "Reinhard 暗部（第二行）");
        eqPixel(decoded.image(), 2, 0, WHITE_255, "Reinhard 高光饱和到白");
        eqPixel(decoded.image(), 3, 0, WHITE_255, "Reinhard 高光饱和到白（第二列）");

        // 色度保持：中位亮度 0.5，彩色像素 (200,100,50) 应得到 (255,230,169)，白像素得到 (254,254,254)
        int[][] coloured = new int[2][4];
        Arrays.fill(coloured[0], rgbe(32, 32, 32, 130));
        Arrays.fill(coloured[1], rgbe(32, 32, 32, 130));
        coloured[0][1] = rgbe(200, 100, 50, 133);
        coloured[1][3] = rgbe(255, 255, 255, 135);
        RadianceHdr.Decoded colourDecoded = decode(header("-Y 2 +X 4", flat(coloured)));
        near(0.5, colourDecoded.medianLuminance(), 1e-12, "色度用例中位亮度");
        near(0.9, colourDecoded.exposure(), 1e-15, "色度用例曝光 0.45/0.5");
        eqPixel(colourDecoded.image(), 1, 0, 0xFFFFE6A9, "Reinhard 彩色 (255,230,169)：色度保持");
        eqPixel(colourDecoded.image(), 3, 1, WHITE_254, "Reinhard 近白 (254,254,254)");
        eqPixel(colourDecoded.image(), 0, 1, GRAY_151, "Reinhard 低亮度仍是 151");

        // E == 0 是黑，不参与缩放；全黑图的中位亮度为 0，被 1e-6 下限钳住而不产生 NaN/Inf
        int[][] black = new int[2][4];
        Arrays.fill(black[0], rgbe(0, 0, 0, 0));
        Arrays.fill(black[1], rgbe(77, 88, 99, 0));
        RadianceHdr.Decoded blackDecoded = decode(header("-Y 2 +X 4", flat(black)));
        eq(0.0, blackDecoded.medianLuminance(), "全黑图中位亮度为 0");
        near(450000.0, blackDecoded.exposure(), 1e-6, "全黑图曝光被 1e-6 钳住（0.45/1e-6）");
        for (int y = 0; y < 2; y++)
            for (int x = 0; x < 4; x++) eqPixel(blackDecoded.image(), x, y, 0xFF000000, "E==0 必须是纯黑");
        check((blackDecoded.image().getRGB(0, 0) >>> 24) == 0xFF, "黑色像素仍然不透明");

        // 单个采样也能算中位数（1x1）
        RadianceHdr.Decoded single = decode(header("-Y 1 +X 1", flat(new int[][]{{rgbe(128, 128, 128, 135)}})));
        eqPixel(single.image(), 0, 0, GRAY_151, "1x1 图像");
        near(0.00703125, single.exposure(), 1e-15, "1x1 曝光");
    }

    /** 采样网格有界：最大 64x64 个采样点，间距由 ceil(size/64) 决定。 */
    private static void samplerBound() throws IOException {
        // 宽度 4096 时 stride = 64，采样列 0,64,...,4032（64 列）
        // 这里用小一点但结构相同的图，间接验证：构造一条“只有采样列亮”的图，
        // 中位亮度必须只由采样列决定。
        int width = 128;
        int height = 64;
        int[][] rows = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                rows[y][x] = (x % 2 == 0) ? rgbe(200, 200, 200, 136) : rgbe(10, 10, 10, 136);
            }
        }
        // stride = ceil(128/64) = 2，采样列全是偶数 -> 全部是亮像素
        RadianceHdr.Decoded decoded = decode(header("-Y 64 +X 128", rle(rows)));
        near(200.0, decoded.medianLuminance(), 1e-9, "采样列全部命中的中位亮度");
        near(0.45 / 200.0, decoded.exposure(), 1e-15, "采样网格决定曝光");

        int[][] shifted = new int[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                shifted[y][x] = (x % 2 == 0) ? rgbe(10, 10, 10, 136) : rgbe(200, 200, 200, 136);
            }
        }
        RadianceHdr.Decoded shiftedDecoded = decode(header("-Y 64 +X 128", rle(shifted)));
        near(10.0, shiftedDecoded.medianLuminance(), 1e-9, "采样列全命中暗像素");
        near(0.045, shiftedDecoded.exposure(), 1e-15, "采样网格决定曝光（暗版）");
    }

    /** 头信息：签名、BOM、CRLF、注释、未知键、FORMAT 变体。 */
    private static void headerVariants() throws IOException {
        byte[] raster = flat(uniform(4, 2, rgbe(128, 128, 128, 135)));
        byte[] classic = header("-Y 2 +X 4", raster);

        check(RadianceHdr.looksLikeRadiance(classic), "looksLikeRadiance 识别 #?RADIANCE");
        check(!RadianceHdr.looksLikeRadiance("#?OPENEXR\n".getBytes(StandardCharsets.ISO_8859_1)),
                "looksLikeRadiance 拒绝 EXR 签名");
        check(!RadianceHdr.looksLikeRadiance(null), "looksLikeRadiance(null) 为 false");

        // 旧签名
        byte[] oldMagic = header("#?RGBE", "# 旧签名", "-Y 2 +X 4", raster);
        eqPixel(decode(oldMagic).image(), 0, 0, GRAY_151, "#?RGBE 旧签名可解");

        // UTF-8 BOM
        byte[] bom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, classic);
        eqPixel(decode(bom).image(), 1, 1, GRAY_151, "UTF-8 BOM 不影响解析");

        // CRLF 行尾
        String text = "#?RADIANCE\r\nFORMAT=32-bit_rle_rgbe\r\n\r\n-Y 2 +X 4\r\n";
        byte[] crlf = concat(text.getBytes(StandardCharsets.ISO_8859_1), raster);
        eqPixel(decode(crlf).image(), 0, 1, GRAY_151, "CRLF 行尾可解");

        // 注释行、未知键、EXPOSURE/COLORCORR/PRIMARIES 全部忽略
        byte[] richHeader = header("#?RADIANCE",
                "# 手写注释\nSOFTWARE=handmade 1.0\nEXPOSURE=2.0\nCOLORCORR=1 1 1\n"
                        + "PRIMARIES=0.6400 0.3300 0.2900 0.6000 0.1500 0.0600\n",
                "-Y 2 +X 4", raster);
        RadianceHdr.Decoded rich = decode(richHeader);
        eqPixel(rich.image(), 0, 0, GRAY_151, "未知头键被忽略");
        near(0.00703125, rich.exposure(), 1e-15, "EXPOSURE 头不改写本地曝光");

        // FORMAT 两边带空格
        byte[] spaced = concat(("#?RADIANCE\nFORMAT = 32-bit_rle_rgbe\n\n-Y 2 +X 4\n")
                .getBytes(StandardCharsets.ISO_8859_1), raster);
        eqPixel(decode(spaced).image(), 0, 0, GRAY_151, "FORMAT 键值前后空格容错");

        // 缺 FORMAT
        mustReject("缺 FORMAT", "缺少 FORMAT",
                () -> concat("#?RADIANCE\nSOFTWARE=x\n\n-Y 2 +X 4\n".getBytes(StandardCharsets.ISO_8859_1), raster));
        // 重复 FORMAT
        mustReject("重复 FORMAT", "重复的 FORMAT",
                () -> concat(("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\nFORMAT=32-bit_rle_rgbe\n\n-Y 2 +X 4\n")
                        .getBytes(StandardCharsets.ISO_8859_1), raster));
        // 错误签名
        mustReject("错误签名", "#?RADIANCE",
                () -> concat("#?OPENEXR\nFORMAT=32-bit_rle_rgbe\n\n-Y 2 +X 4\n"
                        .getBytes(StandardCharsets.ISO_8859_1), raster));
        // 缺空行（分辨率行被当成头信息）
        mustReject("缺空行", "头信息行非法",
                () -> concat("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n-Y 2 +X 4\n"
                        .getBytes(StandardCharsets.ISO_8859_1), raster));
        // 非 ASCII 头信息（键值行里塞原始高位字节）
        mustReject("非 ASCII 头信息", "非 ASCII",
                () -> concat("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\nSOFTWARE=".getBytes(StandardCharsets.ISO_8859_1),
                        new byte[]{(byte) 0xE5, (byte) 0xA4, (byte) 0xA9},
                        "\n\n-Y 2 +X 4\n".getBytes(StandardCharsets.ISO_8859_1), raster));
        // 头信息行超上限
        StringBuilder many = new StringBuilder();
        for (int index = 0; index < 200; index++) many.append("NOTE").append(index).append("=x\n");
        mustReject("头信息行数超限", "头信息超过",
                () -> concat(("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n" + many + "\n-Y 2 +X 4\n")
                        .getBytes(StandardCharsets.ISO_8859_1), raster));
    }

    /** 分辨率行：轴/符号/顺序/数值。 */
    private static void resolutionVariants() throws IOException {
        byte[] raster = flat(uniform(4, 2, rgbe(128, 128, 128, 135)));
        mustReject("分辨率整行缺失", "不完整",
                () -> concat("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n".getBytes(StandardCharsets.ISO_8859_1), raster));
        mustReject("分辨率三段", "分辨率行格式错误",
                () -> header("-Y 2 +X", raster));
        mustReject("分辨率五段", "分辨率行格式错误",
                () -> header("-Y 2 +X 4 +Z 1", raster));
        mustReject("分辨率缺符号", "缺少符号", () -> header("Y 2 +X 4", raster));
        mustReject("分辨率未知轴", "轴标记未知", () -> header("-Z 2 +X 4", raster));
        mustReject("分辨率轴标记过长", "轴标记错误", () -> header("-YY 2 +X 4", raster));
        mustReject("分辨率长度 0", "必须为正", () -> header("-Y 0 +X 4", raster));
        mustReject("分辨率长度带符号", "不是十进制数字", () -> header("-Y 2 +X -4", raster));
        mustReject("分辨率长度非数字", "不是十进制数字", () -> header("-Y 2 +X 4a", raster));
        mustReject("分辨率轴重复", "轴重复", () -> header("-Y 2 +Y 4", raster));
        mustReject("分辨率缺 X 轴（两段）", "分辨率行格式错误", () -> header("-Y 2", raster));
        mustReject("分辨率超硬上限边长", "超过硬上限 16384", () -> header("-Y 2 +X 20000", raster));
        mustReject("分辨率超硬上限像素", "像素", () -> header("-Y 2048 +X 16384", raster));
    }

    /** 调用方上限与算术护栏。 */
    private static void capacityGuards() throws IOException {
        byte[] wide = header("-Y 2 +X 16", flat(uniform(16, 2, rgbe(128, 128, 128, 135))));
        try {
            RadianceHdr.decode(wide, 8, 2048);
            check(false, "超宽必须在解码前被拒");
        } catch (IOException error) {
            check(error.getMessage().contains("超过调用方上限") && error.getMessage().contains("16"),
                    "超宽拒绝信息 → 实际[" + error.getMessage() + "]");
        }
        byte[] tall = header("-Y 2048 +X 4", flat(uniform(4, 2048, rgbe(128, 128, 128, 135))));
        try {
            RadianceHdr.decode(tall, 4096, 1024);
            check(false, "超高必须在解码前被拒");
        } catch (IOException error) {
            check(error.getMessage().contains("超过调用方上限") && error.getMessage().contains("2048"),
                    "超高拒绝信息 → 实际[" + error.getMessage() + "]");
        }
        // 上限内的必须成功
        eq(16, decode(wide).image().getWidth(), "上限内正常解码");
        // 上限恰好等于图像尺寸
        eq(16, RadianceHdr.decode(wide, 16, 2).image().getWidth(), "上限恰好等于尺寸时允许");

        // 非法调用参数
        try {
            RadianceHdr.decode(null, MAX_W, MAX_H);
            check(false, "null 数据必须被拒");
        } catch (IllegalArgumentException expected) {
            check(true, "null 数据抛 IllegalArgumentException");
        }
        try {
            RadianceHdr.decode(wide, 0, MAX_H);
            check(false, "上限 0 必须被拒");
        } catch (IllegalArgumentException expected) {
            check(true, "上限 0 抛 IllegalArgumentException");
        }
        try {
            RadianceHdr.decode(wide, -8, -8);
            check(false, "负上限必须被拒");
        } catch (IllegalArgumentException expected) {
            check(true, "负上限抛 IllegalArgumentException");
        }
    }

    /** 截断：头信息、分辨率行、raw 扫描线、RLE 游程 / 字面量。 */
    private static void truncatedStreams() throws IOException {
        int[][] rows = markerImage();
        byte[] rleFile = header("-Y 4 +X 16", rle(rows));
        byte[] rawFile = header("-Y 4 +X 16", flat(rows));

        // raw：少 1 个像素的 1 个字节
        mustReject("raw 扫描线截断", "截断", () -> slice(rawFile, rawFile.length - 1));
        // raw：少一整行
        mustReject("raw 缺一整行", "截断", () -> slice(rawFile, rawFile.length - 64));

        // RLE：去掉最后一个通道的最后一个字节
        mustReject("RLE 尾部截断", "截断", () -> slice(rleFile, rleFile.length - 1));
        // RLE：在扫描线头部声明之后立刻断掉
        int rasterStart = rleFile.length - rle(rows).length;
        mustReject("RLE 声明后截断", "截断", () -> slice(rleFile, rasterStart + 6));
        // RLE：只给 2 个签名字节
        mustReject("RLE 签名不完整", "截断", () -> slice(rleFile, rasterStart + 2));
        // 头信息断在签名之后
        mustReject("头信息截断", "不完整", () -> "#?RADIANCE".getBytes(StandardCharsets.ISO_8859_1));
        // 分辨率行没有换行就结束
        mustReject("分辨率行截断", "不完整",
                () -> "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 4 +X 16".getBytes(StandardCharsets.ISO_8859_1));
        // 空文件
        mustReject("空文件", "不完整", () -> new byte[0]);
    }

    /** RLE 结构错误必须显式报错，不得读越界或静默错解。 */
    private static void invalidRuns() throws IOException {
        // 声明宽度与图像宽度不符
        byte[] mismatched = concat(
                "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 16\n".getBytes(StandardCharsets.ISO_8859_1),
                new byte[]{0x02, 0x02, 0x00, 0x0F});
        expectError("RLE 声明宽度不符", mismatched, MAX_W, MAX_H, "声明宽度 15");

        // 字面量计数 0
        byte[] zeroLiteral = concat(
                "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 8\n".getBytes(StandardCharsets.ISO_8859_1),
                new byte[]{0x02, 0x02, 0x00, 0x08, 0x00});
        expectError("RLE 字面量计数 0", zeroLiteral, MAX_W, MAX_H, "计数为 0");

        // 游程超出扫描线
        byte[] runOverflow = concat(
                "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 8\n".getBytes(StandardCharsets.ISO_8859_1),
                new byte[]{0x02, 0x02, 0x00, 0x08, (byte) 0xC8, 0x10});
        expectError("RLE 游程越界", runOverflow, MAX_W, MAX_H, "游程 72 超出");

        // 字面量超出扫描线
        byte[] literalOverflow = concat(
                "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 8\n".getBytes(StandardCharsets.ISO_8859_1),
                new byte[]{0x02, 0x02, 0x00, 0x08, 0x0C, 1, 2, 3});
        expectError("RLE 字面量越界", literalOverflow, MAX_W, MAX_H, "字面量 12 超出");

        // 游程值缺失（计数后面没有值）
        byte[] missingRunValue = concat(
                "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 8\n".getBytes(StandardCharsets.ISO_8859_1),
                new byte[]{0x02, 0x02, 0x00, 0x08, (byte) 0x88});
        expectError("RLE 游程缺值", missingRunValue, MAX_W, MAX_H, "截断");

        // 通道之间断掉：通道 0 完整，通道 1 声明 8 个字面量但只给了 3 个字节
        byte[] channelCut = concat(
                "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 8\n".getBytes(StandardCharsets.ISO_8859_1),
                new byte[]{0x02, 0x02, 0x00, 0x08,
                        (byte) 0x88, 0x01,
                        0x08, 0x11, 0x22, 0x33});
        expectError("RLE 通道中途截断", channelCut, MAX_W, MAX_H, "截断");
    }

    /** 旧式 RLE 与 XYZE / EXR：必须显式拒绝，不能静默当成 RGB 解。 */
    private static void legacyAndUnsupported() throws IOException {
        byte[] raster = flat(uniform(4, 2, rgbe(128, 128, 128, 135)));

        // FORMAT=32-bit_rle_xyze
        byte[] xyze = concat("#?RADIANCE\nFORMAT=32-bit_rle_xyze\n\n-Y 2 +X 4\n"
                .getBytes(StandardCharsets.ISO_8859_1), raster);
        expectError("XYZE 被拒", xyze, MAX_W, MAX_H, "32-bit_rle_xyze");
        expectError("XYZE 报“不受支持”", xyze, MAX_W, MAX_H, "不受支持");

        // 其他任意 FORMAT
        byte[] odd = concat("#?RADIANCE\nFORMAT=32-bit_rle_rgb\n\n-Y 2 +X 4\n"
                .getBytes(StandardCharsets.ISO_8859_1), raster);
        expectError("未知 FORMAT 被拒", odd, MAX_W, MAX_H, "32-bit_rle_rgb");

        // 旧式 RLE：像素 (1,1,1,count) 必须报错而不是被当成 1 号样本
        byte[] legacy = concat("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 2 +X 4\n"
                .getBytes(StandardCharsets.ISO_8859_1),
                new byte[]{1, 1, 1, 4, 40, 40, 40, (byte) 136,
                        40, 40, 40, (byte) 136, 40, 40, 40, (byte) 136,
                        40, 40, 40, (byte) 136, 40, 40, 40, (byte) 136,
                        40, 40, 40, (byte) 136});
        expectError("旧式 RLE 被拒", legacy, MAX_W, MAX_H, "旧式 RLE");

        // (1,1,1,1) 是合法像素（E=1），不是重复标记
        byte[] tinyValue = flat(new int[][]{{rgbe(1, 1, 1, 1), rgbe(9, 9, 9, 128), rgbe(9, 9, 9, 128), rgbe(9, 9, 9, 128)}});
        RadianceHdr.Decoded decoded = decode(header("-Y 1 +X 4", tinyValue));
        eq(4, decoded.image().getWidth(), "(1,1,1,1) 视为合法近黑像素");
        check((decoded.image().getRGB(0, 0) >>> 24) == 0xFF, "(1,1,1,1) 输出不透明");
    }

    /** 退化输入：只有签名、只有头信息、单像素 RLE 边界（宽度 8 与 32767 之外走 raw）。 */
    private static void degenerateInputs() throws IOException {
        mustReject("只有签名", "不完整", () -> "#?RADIANCE\n".getBytes(StandardCharsets.ISO_8859_1));
        mustReject("只有签名无换行", "不完整", () -> "#?RADIANCE".getBytes(StandardCharsets.ISO_8859_1));

        // 宽度 7：Radiance 不会 RLE 编码这么窄的扫描线，raw 也要能解
        int[][] narrow = uniform(7, 1, rgbe(128, 128, 128, 135));
        RadianceHdr.Decoded decoded = decode(header("-Y 1 +X 7", flat(narrow)));
        eq(7, decoded.image().getWidth(), "宽度 7 走 raw 分支");
        eqPixel(decoded.image(), 6, 0, GRAY_151, "宽度 7 的像素值");

        // 空 raster（只有头没有像素）
        mustReject("头完整但无像素", "截断", () -> header("-Y 1 +X 7", new byte[0]));

        // 单行平坦流（不 RLE）
        byte[] oneRow = flat(new int[][]{{rgbe(0, 0, 0, 0), rgbe(255, 255, 255, 141),
                rgbe(255, 255, 255, 141), rgbe(255, 255, 255, 141)}});
        RadianceHdr.Decoded mixed = decode(header("-Y 1 +X 4", oneRow));
        eqPixel(mixed.image(), 0, 0, 0xFF000000, "黑像素保持黑");
        check((mixed.image().getRGB(1, 0) >>> 24) == 0xFF, "亮像素不透明");
    }

    // ── 辅助比较 ─────────────────────────────────────────────────────────────

    private static int[] decodedFlat(int[][] rows) throws IOException {
        RadianceHdr.Decoded decoded = decode(header("-Y " + rows.length + " +X " + rows[0].length, flat(rows)));
        int[] colors = new int[rows.length * rows[0].length];
        int at = 0;
        for (int y = 0; y < rows.length; y++)
            for (int x = 0; x < rows[0].length; x++) colors[at++] = decoded.image().getRGB(x, y);
        return colors;
    }

    private static void assertMirror(BufferedImage a, BufferedImage b, String axis, String name) {
        check(a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight(), name + " → 尺寸一致");
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int sourceX = "horizontal".equals(axis) ? a.getWidth() - 1 - x : x;
                int sourceY = "vertical".equals(axis) ? a.getHeight() - 1 - y : y;
                check(a.getRGB(sourceX, sourceY) == b.getRGB(x, y),
                        name + " → (" + x + "," + y + ") 期望[" + hex(a.getRGB(sourceX, sourceY))
                                + "] 实际[" + hex(b.getRGB(x, y)) + "]");
            }
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) {
            if (text.length() > 0) text.append(' ');
            text.append(String.format(Locale.ROOT, "%02X", value & 0xFF));
        }
        return text.toString();
    }
}
