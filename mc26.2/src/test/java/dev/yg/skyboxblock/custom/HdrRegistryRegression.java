package dev.yg.skyboxblock.custom;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Real 26.2 registry import regression for EXR/TX/JPG and no byte-size limit. */
public final class HdrRegistryRegression {
    private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name+" warnings="+CustomSkyRegistry.warnings());}
    private static Path game(Path root,String name,String picture)throws Exception{Path g=root.resolve(name),s=g.resolve("config/skyboxblock/skyboxes");Files.createDirectories(s);Files.writeString(s.resolve("01_case.conf"),"enable = true\ncase = { id = \"skyboxblock:test\" picture = \""+picture+"\" creative_inventory = true }\n",StandardCharsets.UTF_8);return g;}
    private static Path file(Path g,String n){return g.resolve("config/skyboxblock/skyboxes").resolve(n);}
    private static Path out(Path g){return g.resolve("config/skyboxblock/generated/assets/skyboxblock/textures/sky/test.png");}
    public static void main(String[] args)throws Exception{Path root=args.length>0?Path.of(args[0]):Files.createTempDirectory("skybox-262-formats-");
        Path exr=game(root,"exr","scene.exr");Files.write(file(exr,"scene.exr"),OpenExrRegression.sampleExr(false,false));CustomSkyRegistry.load(exr);check(CustomSkyRegistry.styles().size()==1,"EXR style");check(ImageIO.read(out(exr).toFile()).getWidth()==4,"EXR texture");
        Path tx=game(root,"tx","scene.tx");Files.write(file(tx,"scene.tx"),OpenExrRegression.sampleExr(true,false));CustomSkyRegistry.load(tx);check(CustomSkyRegistry.styles().size()==1,"TX style");check(ImageIO.read(out(tx).toFile()).getHeight()==2,"TX texture");
        Path jpg=game(root,"jpg","scene.JPG");BufferedImage source=new BufferedImage(4,2,BufferedImage.TYPE_INT_RGB);ImageIO.write(source,"JPEG",file(jpg,"scene.JPG").toFile());Files.write(file(jpg,"scene.JPG"),new byte[17*1024*1024],java.nio.file.StandardOpenOption.APPEND);CustomSkyRegistry.load(jpg);check(CustomSkyRegistry.styles().size()==1,"large JPG style");check(ImageIO.read(out(jpg).toFile()).getWidth()==4,"large JPG texture");
        System.out.println("PASS: "+checks+" registry format assertions");}
}
