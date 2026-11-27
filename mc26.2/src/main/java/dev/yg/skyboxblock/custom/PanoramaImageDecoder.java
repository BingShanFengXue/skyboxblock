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

/** Dispatches PNG, Radiance HDR, OpenEXR/TX and JPEG panorama imports. */
public final class PanoramaImageDecoder {
    static { ImageIO.setUseCache(false); }
    private PanoramaImageDecoder() { }
    public record Decoded(BufferedImage image, String format, double exposure, double medianLuminance) { }

    public static Decoded decode(Path file, int maxWidth, int maxHeight) throws IOException {
        String lower=file.getFileName().toString().toLowerCase(Locale.ROOT);
        if(lower.endsWith(".hdr")){RadianceHdr.Decoded d=RadianceHdr.decode(Files.readAllBytes(file),maxWidth,maxHeight);return new Decoded(d.image(),"Radiance HDR",d.exposure(),d.medianLuminance());}
        if(lower.endsWith(".exr")||lower.endsWith(".tx")){
            byte[] data=Files.readAllBytes(file);
            if(OpenExr.looksLike(data)){OpenExr.Decoded d=OpenExr.decode(data,maxWidth,maxHeight);return new Decoded(d.image(),lower.endsWith(".tx")?"OpenEXR TX":"OpenEXR",d.exposure(),d.medianLuminance());}
            if(lower.endsWith(".exr"))throw new IOException("不是 OpenEXR：EXR 魔数不匹配");
            BufferedImage image=readImage(file,maxWidth,maxHeight);
            if(image==null)throw new IOException("TX 文件不是 OpenEXR，且 JDK ImageIO 无法解析");
            return new Decoded(image,"ImageIO TX",1.0,Double.NaN);
        }
        BufferedImage image=readImage(file,maxWidth,maxHeight);
        if(image==null)throw new IOException("图片无法解析：需要 PNG、JPG、Radiance HDR、OpenEXR 或 TX");
        return new Decoded(image,lower.endsWith(".jpg")||lower.endsWith(".jpeg")?"JPEG":"ImageIO",1.0,Double.NaN);
    }

    /** Check the image header before read(0) can allocate a raster. */
    private static BufferedImage readImage(Path file,int maxWidth,int maxHeight)throws IOException {
        if(maxWidth<=0||maxHeight<=0)throw new IOException("图片尺寸上限必须为正数");
        try(InputStream source=Files.newInputStream(file);ImageInputStream input=ImageIO.createImageInputStream(source)){
            if(input==null)throw new IOException("无法建立图片输入流");
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())return null;
            ImageReader reader=readers.next();
            try{
                // Read dimensions from the header first; never request a raster until both limits pass.
                reader.setInput(input,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                long pixels=(long)width*height,pixelBudget=(long)maxWidth*maxHeight;
                if(width<=0||height<=0||width>maxWidth||height>maxHeight||pixels>pixelBudget)
                    throw new IOException("图片尺寸 "+width+"x"+height+" 超过上限 "+maxWidth+"x"+maxHeight+"（"+pixelBudget+" 像素）");
                return reader.read(0);
            }finally{reader.dispose();}
        }
    }
}
