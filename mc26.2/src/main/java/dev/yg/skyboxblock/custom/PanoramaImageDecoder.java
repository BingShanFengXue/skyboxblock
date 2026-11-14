package dev.yg.skyboxblock.custom;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Dispatches PNG, Radiance HDR, OpenEXR/TX and JPEG panorama imports. */
public final class PanoramaImageDecoder {
    static { ImageIO.setUseCache(false); }
    private PanoramaImageDecoder() { }
    public record Decoded(BufferedImage image, String format, double exposure, double medianLuminance) { }
    public static Decoded decode(Path file, int maxWidth, int maxHeight) throws IOException {
        String lower=file.getFileName().toString().toLowerCase(Locale.ROOT);
        if(lower.endsWith(".hdr")){RadianceHdr.Decoded d=RadianceHdr.decode(Files.readAllBytes(file),maxWidth,maxHeight);return new Decoded(d.image(),"Radiance HDR",d.exposure(),d.medianLuminance());}
        if(lower.endsWith(".exr")||lower.endsWith(".tx")){byte[] data=Files.readAllBytes(file);if(OpenExr.looksLike(data)){OpenExr.Decoded d=OpenExr.decode(data,maxWidth,maxHeight);return new Decoded(d.image(),lower.endsWith(".tx")?"OpenEXR TX":"OpenEXR",d.exposure(),d.medianLuminance());}if(lower.endsWith(".exr"))throw new IOException("不是 OpenEXR：EXR 魔数不匹配");BufferedImage image=read(file);if(image==null)throw new IOException("TX 文件不是 OpenEXR，且 JDK ImageIO 无法解析");return new Decoded(image,"ImageIO TX",1.0,Double.NaN);}
        BufferedImage image=read(file);if(image==null)throw new IOException("图片无法解析：需要 PNG、JPG、Radiance HDR、OpenEXR 或 TX");return new Decoded(image,lower.endsWith(".jpg")||lower.endsWith(".jpeg")?"JPEG":"ImageIO",1.0,Double.NaN);
    }
    private static BufferedImage read(Path file)throws IOException{try(InputStream input=Files.newInputStream(file)){return ImageIO.read(input);}}
}
