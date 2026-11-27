package dev.yg.skyboxblock.custom;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

/** Dispatches all supported custom panorama formats while keeping one registry output contract. */
public final class PanoramaImageDecoder {
    static { ImageIO.setUseCache(false); }

    private PanoramaImageDecoder() { }

    public record Decoded(BufferedImage image, String format, double exposure, double medianLuminance) { }

    public static Decoded decode(Path file, int maxWidth, int maxHeight) throws IOException {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".hdr")) {
            RadianceHdr.Decoded decoded = RadianceHdr.decode(Files.readAllBytes(file), maxWidth, maxHeight);
            return new Decoded(decoded.image(), "Radiance HDR", decoded.exposure(), decoded.medianLuminance());
        }
        if (lower.endsWith(".exr") || lower.endsWith(".tx")) {
            byte[] data = Files.readAllBytes(file);
            if (OpenExr.looksLike(data)) {
                OpenExr.Decoded decoded = OpenExr.decode(data, maxWidth, maxHeight);
                return new Decoded(decoded.image(), lower.endsWith(".tx") ? "OpenEXR TX" : "OpenEXR",
                        decoded.exposure(), decoded.medianLuminance());
            }
            if (lower.endsWith(".exr")) throw new IOException("不是 OpenEXR：EXR 魔数不匹配");
            // A few tools use .tx as a renamed ordinary image. Keep that useful case working.
            BufferedImage image = readImage(file, maxWidth, maxHeight);
            if (image == null) throw new IOException("TX 文件不是 OpenEXR，且 JDK ImageIO 无法解析");
            return new Decoded(image, "ImageIO TX", 1.0, Double.NaN);
        }
        BufferedImage image = readImage(file, maxWidth, maxHeight);
        if (image == null) throw new IOException("图片无法解析：需要 PNG、JPG、Radiance HDR、OpenEXR 或 TX");
        String format = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "JPEG" : "ImageIO";
        return new Decoded(image, format, 1.0, Double.NaN);
    }

    /** Check the image header before read(0) can allocate a raster. */
    private static BufferedImage readImage(Path file, int maxWidth, int maxHeight) throws IOException {
        if (maxWidth <= 0 || maxHeight <= 0) throw new IOException("图片尺寸上限必须为正数");
        try (InputStream source = Files.newInputStream(file);
             ImageInputStream input = ImageIO.createImageInputStream(source)) {
            if (input == null) throw new IOException("无法建立图片输入流");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                // Width/height are read from the image header; ignore unrelated metadata and keep the
                // stream forward-only. The full decoded raster is requested only after both budgets pass.
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                long pixelBudget = (long) maxWidth * maxHeight;
                if (width <= 0 || height <= 0 || width > maxWidth || height > maxHeight
                        || pixels > pixelBudget) {
                    throw new IOException("图片尺寸 " + width + "x" + height + " 超过上限 "
                            + maxWidth + "x" + maxHeight + "（" + pixelBudget + " 像素）");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }
}
