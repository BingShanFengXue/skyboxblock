package dev.yg.skyboxblock.custom;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Dependency-free OpenEXR decoder shared with the 1.21.1 importer. */
public final class OpenExr {
    private static final int MAGIC = 0x01312f76;
    private static final int MAX_DIMENSION = 16384;
    private static final long MAX_PIXELS = 16_777_216L;
    private static final double LR = 0.2126, LG = 0.7152, LB = 0.0722, TARGET = 0.45;
    private static final int GRID = 64;
    private OpenExr() { }
    public record Decoded(BufferedImage image, double exposure, double medianLuminance, int width, int height, boolean tiled) { }
    public static boolean looksLike(byte[] bytes) { return bytes != null && bytes.length >= 4 && (bytes[0]&255)==0x76 && (bytes[1]&255)==0x2f && (bytes[2]&255)==0x31 && (bytes[3]&255)==1; }
    public static Decoded decode(byte[] bytes, int maxWidth, int maxHeight) throws IOException {
        if (bytes == null) throw new IllegalArgumentException("EXR 数据为 null");
        if (maxWidth <= 0 || maxHeight <= 0) throw new IllegalArgumentException("EXR 尺寸上限必须为正数");
        Cursor cursor = new Cursor(bytes, 0); if (cursor.i32() != MAGIC) throw new IOException("不是 OpenEXR：魔数不匹配");
        int versionFlags=cursor.i32(), version=versionFlags&255, flags=versionFlags&~255;
        if (version<1||version>2) throw new IOException("OpenEXR 版本不受支持："+version);
        if ((flags&0x1000)!=0) throw new IOException("OpenEXR multipart 文件不受支持");
        if ((flags&0x8000)!=0) throw new IOException("OpenEXR deep 文件不受支持");
        Header h=readHeader(cursor,(flags&0x200)!=0,maxWidth,maxHeight);
        float[] r=new float[h.width*h.height],g=new float[r.length],b=new float[r.length];
        decodePixels(bytes,cursor.pos,h,r,g,b); return toneMap(r,g,b,h.width,h.height,h.tiled);
    }
    private static Header readHeader(Cursor cursor, boolean tiled, int maxWidth, int maxHeight) throws IOException {
        List<Channel> channels=new ArrayList<>(); int minX=0,minY=0,maxX=-1,maxY=-1,compression=-1; TileInfo tile=null;
        while(true){
            String name=cursor.cstring(256,"属性名"); if(name.isEmpty())break; cursor.cstring(256,"属性类型 "+name); long sizeLong=cursor.u32();
            if(sizeLong>Integer.MAX_VALUE||sizeLong>cursor.remaining())throw new IOException("OpenEXR 属性 "+name+" 长度非法："+sizeLong);
            int size=(int)sizeLong; byte[] value=cursor.bytes(size);
            switch(name){
                case "channels" -> parseChannels(value,channels);
                case "dataWindow" -> {if(size!=16)throw new IOException("OpenEXR dataWindow 长度错误："+size);IntCursor c=new IntCursor(value);minX=c.i32();minY=c.i32();maxX=c.i32();maxY=c.i32();}
                case "compression" -> {if(size<1)throw new IOException("OpenEXR compression 属性为空");compression=value[0]&255;}
                case "tiles" -> {if(size<9)throw new IOException("OpenEXR tiles 属性过短");IntCursor c=new IntCursor(value);tile=new TileInfo(checkedInt(c.u32(),"tile width"),checkedInt(c.u32(),"tile height"),c.u8());}
                default -> { }
            }
        }
        if(channels.isEmpty())throw new IOException("OpenEXR 缺少 channels 属性"); if(maxX<minX||maxY<minY)throw new IOException("OpenEXR dataWindow 无效");
        long widthLong=(long)maxX-minX+1L,heightLong=(long)maxY-minY+1L,pixels=widthLong*heightLong;
        if(widthLong>MAX_DIMENSION||heightLong>MAX_DIMENSION)throw new IOException("OpenEXR 图像边长超过硬上限 "+MAX_DIMENSION);
        if(pixels>MAX_PIXELS)throw new IOException("OpenEXR 图像像素超过硬上限 "+MAX_PIXELS);
        if(widthLong>maxWidth||heightLong>maxHeight)throw new IOException("OpenEXR 图像 "+widthLong+"x"+heightLong+" 超过调用方上限 "+maxWidth+"x"+maxHeight);
        if(compression<0)throw new IOException("OpenEXR 缺少 compression 属性");
        if(compression!=0&&compression!=2&&compression!=3)throw new IOException("OpenEXR compression 不受支持："+compression+"（支持 NONE、ZIPS、ZIP）");
        if(tiled&&tile==null)throw new IOException("OpenEXR tiled 文件缺少 tiles 属性"); if(!tiled&&tile!=null)throw new IOException("OpenEXR tiles 属性与 scanline 版本冲突");
        for(Channel c:channels) {if(c.xSampling!=1||c.ySampling!=1)throw new IOException("OpenEXR 通道采样率不受支持："+c.name);if(c.pixelType<0||c.pixelType>2)throw new IOException("OpenEXR 通道类型不受支持："+c.name);}
        return new Header((int)widthLong,(int)heightLong,minX,minY,maxX,maxY,channels,selectChannels(channels),compression,tiled,tile);
    }
    private static void parseChannels(byte[] value,List<Channel> out)throws IOException{IntCursor c=new IntCursor(value);while(c.remaining()>0){String name=c.cstring(256,"通道名");if(name.isEmpty())break;if(c.remaining()<16)throw new IOException("OpenEXR 通道记录截断："+name);int type=c.i32();c.u8();c.skip(3);out.add(new Channel(name,type,c.i32(),c.i32()));}if(out.isEmpty())throw new IOException("OpenEXR channels 为空");}
    private static ChannelSelector selectChannels(List<Channel> channels)throws IOException{int r=find(channels,"R"),g=find(channels,"G"),b=find(channels,"B"),y=find(channels,"Y");if(r<0&&g<0&&b<0&&y>=0)return new ChannelSelector(y,y,y);if(r<0||g<0||b<0)throw new IOException("OpenEXR 需要 R/G/B 或 Y 通道；实际通道："+channels.stream().map(Channel::name).toList());return new ChannelSelector(r,g,b);}
    private static int find(List<Channel> channels,String wanted){for(int i=0;i<channels.size();i++){String n=channels.get(i).name;int dot=n.lastIndexOf('.');if((dot<0?n:n.substring(dot+1)).equalsIgnoreCase(wanted))return i;}return -1;}
    private static void decodePixels(byte[] bytes,int offset,Header h,float[] r,float[] g,float[] b)throws IOException{Cursor c=new Cursor(bytes,offset);if(h.tiled){int count=tileOffsetCount(h.width,h.height,h.tile);long[] offsets=new long[count];for(int i=0;i<count;i++)offsets[i]=c.u64();for(long chunk:offsets)if(chunk>0)decodeTile(bytes,chunk,h,r,g,b);}else{int lines=linesPerChunk(h.compression),count=(h.height+lines-1)/lines;long[] offsets=new long[count];for(int i=0;i<count;i++)offsets[i]=c.u64();for(long chunk:offsets)if(chunk>0)decodeScanlineChunk(bytes,chunk,h,r,g,b);}}
    private static void decodeScanlineChunk(byte[] bytes,long chunk,Header h,float[] r,float[] g,float[] b)throws IOException{Cursor c=at(bytes,chunk);int yStart=c.i32(),size=checkedSize(c.u32(),"scanline chunk");byte[] compressed=c.bytes(size);int lines=Math.min(linesPerChunk(h.compression),h.maxY-yStart+1);if(lines<=0||yStart<h.minY||yStart>h.maxY)throw new IOException("OpenEXR scanline y 越界："+yStart);unpackRows(decompress(compressed,expectedBytes(h,h.width,lines),h.compression),h,h.width,lines,yStart-h.minY,0,r,g,b);}
    private static void decodeTile(byte[] bytes,long chunk,Header h,float[] r,float[] g,float[] b)throws IOException{Cursor c=at(bytes,chunk);int tileX=c.i32(),tileY=c.i32(),levelX=c.i32(),levelY=c.i32(),size=checkedSize(c.u32(),"tile chunk");byte[] compressed=c.bytes(size);if(levelX!=0||levelY!=0)return;int x0=tileX*h.tile.xSize+h.minX,y0=tileY*h.tile.ySize+h.minY;if(x0>h.maxX||y0>h.maxY)return;int width=Math.min(h.tile.xSize,h.maxX-x0+1),height=Math.min(h.tile.ySize,h.maxY-y0+1);unpackRows(decompress(compressed,expectedBytes(h,width,height),h.compression),h,width,height,y0-h.minY,x0-h.minX,r,g,b);}
    private static void unpackRows(byte[] raw,Header h,int width,int height,int yOffset,int xOffset,float[] r,float[] g,float[] b)throws IOException{IntCursor c=new IntCursor(raw);for(int row=0;row<height;row++){int y=yOffset+row;for(int ci=0;ci<h.channels.size();ci++){Channel ch=h.channels.get(ci);boolean keep=ci==h.selector.r||ci==h.selector.g||ci==h.selector.b;for(int x=0;x<width;x++){float sample=readSample(c,ch.pixelType);if(!keep)continue;int tx=xOffset+x;if(tx<0||tx>=h.width||y<0||y>=h.height)continue;int target=y*h.width+tx;if(ci==h.selector.r)r[target]=sample;if(ci==h.selector.g)g[target]=sample;if(ci==h.selector.b)b[target]=sample;}}}}
    private static float readSample(IntCursor c,int type)throws IOException{return switch(type){case 0->(float)c.u32();case 1->half(c.u16());case 2->Float.intBitsToFloat(c.i32());default->throw new IOException("OpenEXR sample type 不受支持："+type);};}
    private static byte[] decompress(byte[] compressed,int expected,int compression)throws IOException{if(compression==0){if(compressed.length<expected)throw new IOException("OpenEXR 未压缩数据截断");return compressed.length==expected?compressed:Arrays.copyOf(compressed,expected);}Inflater inflater=new Inflater();inflater.setInput(compressed);byte[] packed=new byte[expected];try{int written=inflater.inflate(packed);if(written!=expected||!inflater.finished())throw new IOException("OpenEXR ZIP 数据长度错误："+written+"/"+expected);}catch(DataFormatException e){throw new IOException("OpenEXR ZIP 数据损坏",e);}finally{inflater.end();}byte[] restored=new byte[expected];int half=(expected+1)/2;for(int i=0;i<half;i++)restored[i*2]=packed[i];for(int i=half;i<expected;i++)restored[(i-half)*2+1]=packed[i];for(int i=1;i<expected;i++)restored[i]=(byte)((restored[i]&255)+(restored[i-1]&255)-128);return restored;}
    private static int expectedBytes(Header h,int width,int height)throws IOException{long perPixel=0;for(Channel c:h.channels)perPixel+=c.bytes();long total=perPixel*width*(long)height;if(total>Integer.MAX_VALUE)throw new IOException("OpenEXR 数据块太大");return(int)total;}
    private static int linesPerChunk(int compression){return compression==3?16:1;}
    private static int tileOffsetCount(int width,int height,TileInfo tile)throws IOException{if(tile==null||tile.xSize<=0||tile.ySize<=0)throw new IOException("OpenEXR tile 尺寸无效");int x=(width+tile.xSize-1)/tile.xSize,y=(height+tile.ySize-1)/tile.ySize,mode=tile.mode&15;if(mode==0)return x*y;if(mode==1){int levels=1+floorLog2(Math.max(width,height)),count=0;for(int level=0;level<levels;level++)count+=levelTiles(width,tile.xSize,level)*levelTiles(height,tile.ySize,level);return count;}if(mode==2){int lx=1+floorLog2(width),ly=1+floorLog2(height),count=0;for(int yl=0;yl<ly;yl++)for(int xl=0;xl<lx;xl++)count+=levelTiles(width,tile.xSize,xl)*levelTiles(height,tile.ySize,yl);return count;}throw new IOException("OpenEXR tile level mode 不受支持："+mode);}
    private static int levelTiles(int size,int tile,int level){int levelSize=Math.max(1,size>>level);return(levelSize+tile-1)/tile;}
    private static int floorLog2(int value){return 31-Integer.numberOfLeadingZeros(value);}
    private static Decoded toneMap(float[] r,float[] g,float[] b,int width,int height,boolean tiled){int sx=Math.max(1,(width+GRID-1)/GRID),sy=Math.max(1,(height+GRID-1)/GRID);double[] samples=new double[((width-1)/sx+1)*((height-1)/sy+1)];int at=0;for(int y=0;y<height;y+=sy)for(int x=0;x<width;x+=sx){int i=y*width+x;samples[at++]=luminance(r[i],g[i],b[i]);}Arrays.sort(samples);double median=(samples.length&1)==1?samples[samples.length/2]:(samples[samples.length/2-1]+samples[samples.length/2])*.5,exposure=TARGET/Math.max(median,1e-6);BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);int[] pixels=((DataBufferInt)image.getRaster().getDataBuffer()).getData();for(int i=0;i<pixels.length;i++){double lum=luminance(r[i],g[i],b[i]);if(!(lum>0)||!Double.isFinite(lum)){pixels[i]=0xff000000;continue;}double gain=exposure/(1+lum*exposure);pixels[i]=0xff000000|(srgb(r[i]*gain)<<16)|(srgb(g[i]*gain)<<8)|srgb(b[i]*gain);}return new Decoded(image,exposure,median,width,height,tiled);}
    private static double luminance(float r,float g,float b){return Math.max(0,LR*finite(r)+LG*finite(g)+LB*finite(b));}
    private static double finite(float value){return Float.isFinite(value)&&value>0?value:0;}
    private static int srgb(double linear){if(!(linear>0)||!Double.isFinite(linear))return 0;double encoded=linear<=.0031308?12.92*linear:1.055*Math.pow(linear,1/2.4)-.055;return Math.max(0,Math.min(255,(int)Math.round(encoded*255)));}
    private record Header(int width,int height,int minX,int minY,int maxX,int maxY,List<Channel> channels,ChannelSelector selector,int compression,boolean tiled,TileInfo tile){}
    private record Channel(String name,int pixelType,int xSampling,int ySampling){int bytes(){return pixelType==1?2:4;}}
    private record ChannelSelector(int r,int g,int b){}
    private record TileInfo(int xSize,int ySize,int mode){}
    private static int checkedInt(long value,String what)throws IOException{if(value>Integer.MAX_VALUE)throw new IOException("OpenEXR "+what+" 超过 int 范围");return(int)value;}
    private static int checkedSize(long value,String what)throws IOException{if(value>Integer.MAX_VALUE)throw new IOException("OpenEXR "+what+" 太大");return(int)value;}
    private static Cursor at(byte[] data,long offset)throws IOException{if(offset<0||offset>data.length-1)throw new IOException("OpenEXR chunk offset 越界："+offset);return new Cursor(data,(int)offset);}
    private static float half(int bits){int sign=(bits>>>15)&1,exponent=(bits>>>10)&31,fraction=bits&1023;if(exponent==0)return(float)((sign==0?1:-1)*Math.scalb(fraction/1024.0,-14));if(exponent==31)return fraction==0?(sign==0?Float.POSITIVE_INFINITY:Float.NEGATIVE_INFINITY):Float.NaN;return(float)((sign==0?1:-1)*Math.scalb(1.0+fraction/1024.0,exponent-15));}
    private static final class Cursor{final byte[] data;int pos;Cursor(byte[] data,int pos){this.data=data;this.pos=pos;}int remaining(){return data.length-pos;}int u8()throws IOException{if(remaining()<1)throw new IOException("OpenEXR 数据截断");return data[pos++]&255;}int i32()throws IOException{return u32Int();}int u32Int()throws IOException{if(remaining()<4)throw new IOException("OpenEXR 数据截断");int v=(data[pos]&255)|((data[pos+1]&255)<<8)|((data[pos+2]&255)<<16)|((data[pos+3]&255)<<24);pos+=4;return v;}long u32()throws IOException{return Integer.toUnsignedLong(u32Int());}long u64()throws IOException{long lo=u32(),hi=u32();return lo|(hi<<32);}String cstring(int max,String what)throws IOException{int start=pos;while(pos<data.length&&data[pos]!=0&&pos-start<max)pos++;if(pos>=data.length||pos-start>=max)throw new IOException("OpenEXR "+what+"过长或未结束");String s=new String(data,start,pos-start,java.nio.charset.StandardCharsets.US_ASCII);pos++;return s;}byte[] bytes(int count)throws IOException{if(count<0||remaining()<count)throw new IOException("OpenEXR 数据截断");byte[] out=Arrays.copyOfRange(data,pos,pos+count);pos+=count;return out;}}
    private static final class IntCursor{final byte[] data;int pos;IntCursor(byte[] data){this.data=data;}int remaining(){return data.length-pos;}int u8(){if(remaining()<1)throw new IllegalArgumentException("EXR attribute truncated");return data[pos++]&255;}int u16(){return u8()|(u8()<<8);}long u32(){return Integer.toUnsignedLong(i32());}int i32(){return u8()|(u8()<<8)|(u8()<<16)|(u8()<<24);}String cstring(int max,String what)throws IOException{int start=pos;while(pos<data.length&&data[pos]!=0&&pos-start<max)pos++;if(pos>=data.length||pos-start>=max)throw new IOException("OpenEXR "+what+"过长");String s=new String(data,start,pos-start,java.nio.charset.StandardCharsets.US_ASCII);pos++;return s;}void skip(int count){if(count<0||remaining()<count)throw new IllegalArgumentException("EXR attribute truncated");pos+=count;}}
}
