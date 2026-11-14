package dev.yg.skyboxblock.custom;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.Deflater;

/** Pure-JDK OpenEXR/TX raw, ZIP and HALF regression for 26.2. */
public final class OpenExrRegression {
    private static void putInt(ByteArrayOutputStream o,int v){o.write(v&255);o.write(v>>>8&255);o.write(v>>>16&255);o.write(v>>>24&255);}
    private static void putLong(ByteArrayOutputStream o,long v){putInt(o,(int)v);putInt(o,(int)(v>>>32));}
    private static void putFloat(ByteArrayOutputStream o,float v){putInt(o,Float.floatToIntBits(v));}
    private static void cs(ByteArrayOutputStream o,String s){o.writeBytes(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));o.write(0);}
    private static void attr(ByteArrayOutputStream o,String n,String t,byte[] d){cs(o,n);cs(o,t);putInt(o,d.length);o.writeBytes(d);}
    private static byte[] channels(int type,String... names){ByteArrayOutputStream o=new ByteArrayOutputStream();for(String n:names){cs(o,n);putInt(o,type);o.write(0);o.write(0);o.write(0);o.write(0);putInt(o,1);putInt(o,1);}o.write(0);return o.toByteArray();}
    private static byte[] window(){ByteArrayOutputStream o=new ByteArrayOutputStream();putInt(o,0);putInt(o,0);putInt(o,3);putInt(o,1);return o.toByteArray();}
    public static byte[] sampleExr(boolean zip,boolean gray){ByteArrayOutputStream f=new ByteArrayOutputStream();putInt(f,0x01312f76);putInt(f,2);attr(f,"channels","chlist",channels(gray?1:2,gray?"Y":"B",gray?"":"G",gray?"":"R"));attr(f,"compression","compression",new byte[]{(byte)(zip?3:0)});attr(f,"dataWindow","box2i",window());attr(f,"displayWindow","box2i",window());attr(f,"lineOrder","lineOrder",new byte[]{0});ByteArrayOutputStream a=new ByteArrayOutputStream();putFloat(a,1);attr(f,"pixelAspectRatio","float",a.toByteArray());ByteArrayOutputStream c=new ByteArrayOutputStream();putFloat(c,0);putFloat(c,0);attr(f,"screenWindowCenter","v2f",c.toByteArray());attr(f,"screenWindowWidth","float",a.toByteArray());f.write(0);byte[] raw=new byte[gray?16:96];if(gray){ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);for(int row=0;row<2;row++)for(int x=0;x<4;x++)b.putShort((short)(0x3c00+x*0x100));}else{ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);for(int row=0;row<2;row++)for(String ch:new String[]{"B","G","R"})for(int x=0;x<4;x++)b.putFloat(ch.equals("R")?x/3f:ch.equals("G")?row:.25f);}byte[] packed=zip?zip(raw):raw;int rowBytes=gray?8:48;if(zip){putLong(f,f.size()+8);putInt(f,0);putInt(f,packed.length);f.writeBytes(packed);}else{long first=f.size()+16,second=first+8L+rowBytes;putLong(f,first);putLong(f,second);putInt(f,0);putInt(f,rowBytes);f.writeBytes(Arrays.copyOfRange(raw,0,rowBytes));putInt(f,1);putInt(f,rowBytes);f.writeBytes(Arrays.copyOfRange(raw,rowBytes,raw.length));}return f.toByteArray();}
    private static byte[] zip(byte[] raw){byte[] p=raw.clone();for(int i=p.length-1;i>0;i--)p[i]=(byte)((raw[i]&255)-(raw[i-1]&255)+128);byte[] inter=new byte[p.length];int at=0;for(int i=0;i<p.length;i+=2)inter[at++]=p[i];for(int i=1;i<p.length;i+=2)inter[at++]=p[i];Deflater d=new Deflater();d.setInput(inter);d.finish();byte[] out=new byte[inter.length+128];int n=d.deflate(out);d.end();return Arrays.copyOf(out,n);}
    private static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);}
    public static void main(String[] args)throws Exception{byte[] raw=sampleExr(false,false),zip=sampleExr(true,false),gray=sampleExr(false,true);check(OpenExr.looksLike(raw),"EXR magic");OpenExr.Decoded a=OpenExr.decode(raw,4096,2048),b=OpenExr.decode(zip,4096,2048),c=OpenExr.decode(gray,4096,2048);check(a.width()==4&&a.height()==2,"raw dimensions");for(int y=0;y<2;y++)for(int x=0;x<4;x++)check(a.image().getRGB(x,y)==b.image().getRGB(x,y),"ZIP parity");check(c.image().getWidth()==4,"half grayscale");System.out.println("PASS: 6 EXR/TX assertions");}
}
