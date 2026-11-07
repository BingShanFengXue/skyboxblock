import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

/**
 * HDR 支持性探针：直接调用**生产 jar 里真实的** CustomSkyRegistry.load(gameDir)，
 * 用 stub 顶掉 MC 注册表类，只看图片链路。
 *
 * <p>不修改模组源码、不改任何 jar；只喂素材、收日志、量生成的 PNG。</p>
 *
 * <p>用法：java -cp &lt;jar;stubs;out&gt; HdrProbe &lt;gameDir&gt; &lt;label&gt; &lt;jsonOut&gt;</p>
 */
public final class HdrProbe {

    public static void main(String[] args) throws Exception {
        Path gameDir = Path.of(args[0]);
        String label = args[1];
        Path jsonOut = Path.of(args[2]);

        Path skyDir = gameDir.resolve("config").resolve("skyboxblock").resolve("skyboxes");
        Path genDir = gameDir.resolve("config").resolve("skyboxblock").resolve("generated");

        long t0 = System.nanoTime();
        Class<?> registry = Class.forName("dev.yg.skyboxblock.custom.CustomSkyRegistry");
        Method load = registry.getMethod("load", Path.class);
        String thrown = "";
        try {
            load.invoke(null, gameDir);
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            thrown = cause.getClass().getName() + ": " + cause.getMessage();
        }
        long loadMs = (System.nanoTime() - t0) / 1_000_000L;

        List<?> styles = (List<?>) registry.getMethod("styles").invoke(null);
        @SuppressWarnings("unchecked")
        List<String> warnings = new ArrayList<>((List<String>) registry.getMethod("warnings").invoke(null));
        String manifest = String.valueOf(registry.getMethod("manifestHash").invoke(null));

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"label\": ").append(quote(label)).append(",\n");
        json.append("  \"modJar\": ").append(quote(System.getProperty("probe.jar", "?"))).append(",\n");
        json.append("  \"javaVersion\": ").append(quote(System.getProperty("java.version"))).append(",\n");
        json.append("  \"imageReaders\": ").append(quote(readerNames())).append(",\n");
        json.append("  \"skyboxesDir\": ").append(quote(skyDir.toString())).append(",\n");
        json.append("  \"loadMs\": ").append(loadMs).append(",\n");
        json.append("  \"loadThrew\": ").append(quote(thrown)).append(",\n");
        json.append("  \"manifestHash\": ").append(quote(manifest)).append(",\n");

        json.append("  \"styles\": [");
        for (int i = 0; i < styles.size(); i++) {
            Object style = styles.get(i);
            Method idM = style.getClass().getMethod("id");
            Method pictureM = style.getClass().getMethod("picture");
            Method panoramaM = style.getClass().getMethod("panorama");
            Method nameM = style.getClass().getMethod("displayName");
            if (i > 0) json.append(',');
            json.append("\n    {")
                    .append("\"id\": ").append(quote(String.valueOf(idM.invoke(style))))
                    .append(", \"picture\": ").append(quote(String.valueOf(pictureM.invoke(style))))
                    .append(", \"panorama\": ").append(quote(String.valueOf(panoramaM.invoke(style))))
                    .append(", \"displayName\": ").append(quote(String.valueOf(nameM.invoke(style))))
                    .append('}');
        }
        json.append("\n  ],\n");

        json.append("  \"warnings\": [");
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) json.append(',');
            json.append("\n    ").append(quote(warnings.get(i)));
        }
        json.append("\n  ],\n");

        json.append("  \"generatedTextures\": [");
        List<String> entries = new ArrayList<>();
        if (Files.isDirectory(genDir.resolve("assets"))) {
            try (Stream<Path> walk = Files.walk(genDir.resolve("assets"))) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".png"))
                        .filter(p -> p.toString().contains("textures" + java.io.File.separator + "sky"))
                        .sorted()
                        .forEach(p -> entries.add(p.toString()));
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) json.append(',');
            json.append("\n    ").append(textureReport(Path.of(entries.get(i))));
        }
        json.append("\n  ],\n");

        json.append("  \"generatedBlockIcons\": [");
        List<String> icons = new ArrayList<>();
        if (Files.isDirectory(genDir.resolve("assets"))) {
            try (Stream<Path> walk = Files.walk(genDir.resolve("assets"))) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".png"))
                        .filter(p -> p.toString().contains("textures" + java.io.File.separator + "block"))
                        .sorted()
                        .forEach(p -> icons.add(p.toString()));
            }
        }
        for (int i = 0; i < icons.size(); i++) {
            if (i > 0) json.append(',');
            json.append("\n    ").append(textureReport(Path.of(icons.get(i))));
        }
        json.append("\n  ]\n}\n");

        Files.createDirectories(jsonOut.getParent());
        Files.writeString(jsonOut, json.toString());

        System.out.println("=== " + label + " ===");
        System.out.println("styles=" + styles.size() + " warnings=" + warnings.size() + " loadMs=" + loadMs);
        for (String warning : warnings) System.out.println("  WARN " + warning);
        for (String entry : entries) System.out.println("  GEN  " + entry);
    }

    /** PNG 头里的真实位深/颜色类型 + 解码后的实际通道极值。 */
    private static String textureReport(Path file) {
        StringBuilder out = new StringBuilder();
        int depth = -1;
        int colorType = -1;
        long bytes = -1;
        int width = -1;
        int height = -1;
        try {
            bytes = Files.size(file);
            byte[] head = new byte[33];
            try (InputStream in = Files.newInputStream(file)) {
                new DataInputStream(in).readFully(head);
            }
            boolean png = (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            if (png && new String(head, 12, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("IHDR")) {
                width = readInt(head, 16);
                height = readInt(head, 20);
                depth = head[24] & 0xFF;
                colorType = head[25] & 0xFF;
            }
        } catch (Exception error) {
            out.append("\"error\": ").append(quote(error.toString())).append(", ");
        }

        int[] extremes = new int[3];
        int decodedType = -1;
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image != null) {
                decodedType = image.getType();
                int maxR = 0, maxG = 0, maxB = 0;
                int stepX = Math.max(1, image.getWidth() / 128);
                int stepY = Math.max(1, image.getHeight() / 64);
                for (int y = 0; y < image.getHeight(); y += stepY) {
                    for (int x = 0; x < image.getWidth(); x += stepX) {
                        int argb = image.getRGB(x, y);
                        maxR = Math.max(maxR, (argb >>> 16) & 0xFF);
                        maxG = Math.max(maxG, (argb >>> 8) & 0xFF);
                        maxB = Math.max(maxB, argb & 0xFF);
                    }
                }
                extremes = new int[] {maxR, maxG, maxB};
            }
        } catch (Exception error) {
            out.append("\"decodeError\": ").append(quote(error.toString())).append(", ");
        }

        out.append("\"file\": ").append(quote(file.getFileName().toString()))
                .append(", \"bytes\": ").append(bytes)
                .append(", \"width\": ").append(width)
                .append(", \"height\": ").append(height)
                .append(", \"ihdrBitDepth\": ").append(depth)
                .append(", \"ihdrColorType\": ").append(colorType)
                .append(", \"decodedImageType\": ").append(decodedType)
                .append(", \"maxChannelAfterDecode\": [")
                .append(extremes[0]).append(", ").append(extremes[1]).append(", ").append(extremes[2]).append(']');
        return "{" + out + "}";
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static String readerNames() {
        StringBuilder out = new StringBuilder();
        for (java.util.Iterator<javax.imageio.spi.ImageReaderSpi> it =
                javax.imageio.spi.IIORegistry.getDefaultInstance()
                        .getServiceProviders(javax.imageio.spi.ImageReaderSpi.class, false);
                it.hasNext(); ) {
            javax.imageio.spi.ImageReaderSpi spi = it.next();
            if (out.length() > 0) out.append('|');
            String[] suffixes = spi.getFileSuffixes();
            out.append(suffixes == null || suffixes.length == 0 ? spi.getFormatNames()[0]
                    : String.join("/", suffixes));
        }
        return out.toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }

    private HdrProbe() { }
}
