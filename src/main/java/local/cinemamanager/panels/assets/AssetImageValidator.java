package local.cinemamanager.panels.assets;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.CRC32;

/** Bounded streaming container checks only. The viewer still performs the full budgeted image decode. */
public final class AssetImageValidator {
    public record Result(String format,int width,int height,long bytes){}
    private static final int MAX_PARTS=4096;
    public static Result validate(Path file,long maximum)throws IOException{
        BasicFileAttributes attributes=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile())throw invalid();long length=attributes.size();
        if(length<12)throw invalid();if(length>maximum||length>256L*1024*1024)throw new IOException("asset_too_large");
        try(var raw=new BufferedInputStream(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS),32768);var in=new Input(raw,length)){
            byte[] head=in.bytes(12);in.unread(head);
            Result result;
            if(Arrays.equals(Arrays.copyOf(head,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))result=png(in,length);
            else if((head[0]&255)==255&&(head[1]&255)==216)result=jpeg(in,length);
            else if(ascii(head,0,"RIFF")&&ascii(head,8,"WEBP"))result=webp(in,length);
            else throw new IOException("asset_unsupported_format");
            BasicFileAttributes after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(in.position!=length||!after.isRegularFile()||after.size()!=length||!after.lastModifiedTime().equals(attributes.lastModifiedTime())||!Objects.equals(after.fileKey(),attributes.fileKey()))throw invalid();return result;
        }
    }
    private static Result png(Input in,long length)throws IOException{
        in.skip(8);int w=0,h=0,color=-1,depth=0,parts=0,frames=0,expectedFrames=0,animationSequence=0,cmf=-1,flg=-1;long dataBytes=0;boolean data=false,dataEnded=false,palette=false,animation=false,frameData=false;
        while(in.position<length){
            if(++parts>MAX_PARTS)throw invalid();long n=in.be32();byte[] kind=in.bytes(4);String type=new String(kind,StandardCharsets.US_ASCII);
            for(byte b:kind)if(!((b>='A'&&b<='Z')||(b>='a'&&b<='z')))throw invalid();
            if((kind[2]&32)!=0||n>length-in.position-4)throw invalid();
            CRC32 crc=new CRC32();crc.update(kind);byte[] head=in.consume(n,crc,26);
            if(crc.getValue()!=in.be32())throw invalid();
            if(parts==1&&!type.equals("IHDR"))throw invalid();
            switch(type){
                case "IHDR"->{if(parts!=1||n!=13)throw invalid();w=be32(head,0);h=be32(head,4);bounds(w,h);depth=head[8]&255;color=head[9]&255;
                    boolean validDepth=switch(color){case 0->depth==1||depth==2||depth==4||depth==8||depth==16;case 2,4,6->depth==8||depth==16;case 3->depth==1||depth==2||depth==4||depth==8;default->false;};
                    if(!validDepth||head[10]!=0||head[11]!=0||(head[12]&255)>1)throw invalid();}
                case "PLTE"->{if(palette||data||n<3||n>768||n%3!=0||color==0||color==4||(color==3&&n/3>(1<<depth)))throw invalid();palette=true;}
                case "IDAT"->{if(dataEnded||color==3&&!palette)throw invalid();
                    for(int i=0;i<head.length&&dataBytes+i<2;i++){if(dataBytes+i==0)cmf=head[i]&255;else flg=head[i]&255;}dataBytes+=n;data=true;if(frames>0)frameData=true;}
                case "IEND"->{if(n!=0||!data||dataBytes<6||cmf<0||flg<0||(cmf&15)!=8||(cmf>>4)>7||(cmf*256+flg)%31!=0||(flg&32)!=0||in.position!=length||animation&&(frames!=expectedFrames||!frameData))throw invalid();return new Result("PNG",w,h,length);}
                case "acTL"->{if(animation||data||n!=8)throw invalid();animation=true;expectedFrames=be32(head,0);if(expectedFrames<1||expectedFrames>MAX_PARTS)throw invalid();}
                case "fcTL"->{if(!animation||n!=26||be32(head,0)!=animationSequence++||frames>0&&!frameData)throw invalid();
                    int fw=be32(head,4),fh=be32(head,8),x=be32(head,12),y=be32(head,16);bounds(fw,fh);if(x<0||y<0||(long)x+fw>w||(long)y+fh>h||(head[24]&255)>2||(head[25]&255)>1)throw invalid();
                    frames++;frameData=false;if(data)dataEnded=true;}
                case "fdAT"->{if(!animation||frames==0||n<5||be32(head,0)!=animationSequence++)throw invalid();frameData=true;dataEnded=true;}
                default->{if((kind[0]&32)==0)throw invalid();if(data)dataEnded=true;}
            }
        }throw invalid();
    }
    private static Result jpeg(Input in,long length)throws IOException{
        if(in.byteValue()!=255||in.byteValue()!=216)throw invalid();int w=0,h=0,parts=0,components=0;boolean scan=false;int pending=-1;
        while(pending>=0||in.position<length){
            if(++parts>MAX_PARTS)throw invalid();int marker;
            if(pending>=0){marker=pending;pending=-1;}else{if(in.byteValue()!=255)throw invalid();do{marker=in.byteValue();}while(marker==255);}
            if(marker==217){if(!scan||w==0||in.position!=length)throw invalid();return new Result("JPEG",w,h,length);}
            if(marker==0||marker==216||marker==1||marker>=208&&marker<=215)throw invalid();
            int n=in.be16();if(n<2||n-2>length-in.position)throw invalid();byte[] header=in.consume(n-2,null,32);
            if(marker>=192&&marker<=207&&marker!=196&&marker!=200&&marker!=204){
                if(w!=0||(marker!=192&&marker!=193&&marker!=194)||n<8||header[0]!=8)throw invalid();
                h=(header[1]&255)*256+(header[2]&255);w=(header[3]&255)*256+(header[4]&255);bounds(w,h);components=header[5]&255;
                if((components!=1&&components!=3&&components!=4)||n!=8+3*components)throw invalid();
                var seen=new HashSet<Integer>();for(int c=0;c<components;c++){int id=header[6+c*3]&255,sampling=header[7+c*3]&255;if(!seen.add(id)||(sampling>>4)<1||(sampling>>4)>4||(sampling&15)<1||(sampling&15)>4||(header[8+c*3]&255)>3)throw invalid();}
            }
            if(marker==218){
                if(w==0||n<6)throw invalid();int count=header[0]&255;if(count<1||count>components||n!=6+2*count)throw invalid();scan=true;
                boolean entropy=false;
                while(in.position<length){int b=in.byteValue();if(b!=255){entropy=true;continue;}int next;do{next=in.byteValue();}while(next==255);if(next==0||next>=208&&next<=215){entropy=true;continue;}pending=next;break;}
                if(!entropy||pending<0)throw invalid();
            }
        }throw invalid();
    }
    private static Result webp(Input in,long length)throws IOException{
        byte[] header=in.bytes(12);if(le32(header,4)+8!=length)throw invalid();int w=0,h=0,parts=0;boolean extended=false,animated=false,anim=false,image=false,alpha=false;
        while(in.position<length){
            if(++parts>MAX_PARTS||length-in.position<8)throw invalid();byte[] chunk=in.bytes(8);String kind=new String(chunk,0,4,StandardCharsets.US_ASCII);long n=le32(chunk,4);if(n+(n&1)>length-in.position)throw invalid();
            if(kind.equals("ANMF")){
                if(!animated||!anim||n<24)throw invalid();byte[] f=in.bytes(16);int x=le24(f,0)*2,y=le24(f,3)*2,fw=le24(f,6)+1,fh=le24(f,9)+1;bounds(fw,fh);
                if((long)x+fw>w||(long)y+fh>h||(f[15]&~3)!=0)throw invalid();long end=in.position+n-16;boolean found=false,frameAlpha=false;
                while(in.position<end){if(++parts>MAX_PARTS||end-in.position<8)throw invalid();byte[] sub=in.bytes(8);String sk=new String(sub,0,4,StandardCharsets.US_ASCII);long sn=le32(sub,4);if(sn+(sn&1)>end-in.position)throw invalid();byte[] sh=in.consume(sn,null,10);
                    if(sk.equals("ALPH")){if(found||frameAlpha)throw invalid();alpha(sh,sn,fw,fh);frameAlpha=true;}
                    else if(sk.equals("VP8 ")||sk.equals("VP8L")){if(found)throw invalid();int[] dimensions=bitstream(sk,sh,sn,frameAlpha);if(dimensions[0]!=fw||dimensions[1]!=fh)throw invalid();found=true;}
                    else throw invalid();if((sn&1)!=0&&in.byteValue()!=0)throw invalid();}
                if(!found)throw invalid();image=true;
            }else{
                byte[] body=in.consume(n,null,10);
                switch(kind){
                    case "VP8X"->{if(parts!=1||n!=10||(body[0]&0xc1)!=0||body[1]!=0||body[2]!=0||body[3]!=0)throw invalid();extended=true;animated=(body[0]&2)!=0;w=le24(body,4)+1;h=le24(body,7)+1;bounds(w,h);}
                    case "ANIM"->{if(!animated||anim||image||n!=6)throw invalid();anim=true;}
                    case "ALPH"->{if(!extended||animated||image||alpha)throw invalid();alpha(body,n,w,h);alpha=true;}
                    case "VP8 ","VP8L"->{if(animated||image)throw invalid();int[] dimensions=bitstream(kind,body,n,alpha);if(extended&&(dimensions[0]!=w||dimensions[1]!=h))throw invalid();w=dimensions[0];h=dimensions[1];image=true;}
                    default->{if(!extended||!(kind.equals("ICCP")||kind.equals("EXIF")||kind.equals("XMP ")))throw invalid();}
                }
            }
            if((n&1)!=0&&in.byteValue()!=0)throw invalid();
        }
        if(!image)throw invalid();return new Result("WebP",w,h,length);
    }
    private static void alpha(byte[] h,long n,int w,int height)throws IOException{if(n<1)throw invalid();int bits=h[0]&255;if((bits&0xc0)!=0||(bits&3)>1||((bits>>4)&3)>1||((bits&3)==0&&n!=1L+(long)w*height))throw invalid();}
    private static int[] bitstream(String type,byte[] h,long n,boolean alpha)throws IOException{
        int w,height;if(type.equals("VP8L")){if(n<5||h[0]!=47||alpha)throw invalid();long bits=le32(h,1);if((bits>>>29)!=0)throw invalid();w=(int)(bits&16383)+1;height=(int)((bits>>>14)&16383)+1;}
        else{if(n<10||(h[0]&1)!=0||(h[0]&0x10)==0||(h[3]&255)!=157||h[4]!=1||h[5]!=42)throw invalid();long partition=((h[0]&255)|((h[1]&255)<<8)|((h[2]&255)<<16))>>>5;if(partition>n-10)throw invalid();w=((h[6]&255)|((h[7]&255)<<8))&16383;height=((h[8]&255)|((h[9]&255)<<8))&16383;}
        bounds(w,height);return new int[]{w,height};
    }
    private static void bounds(int w,int h)throws IOException{if(w<1||h<1||w>8192||h>8192)throw AssetFailure.dimensions(w,h);}
    private static IOException invalid(){return new IOException("asset_invalid_image");}
    private static int be32(byte[] b,int p){return (b[p]&255)<<24|(b[p+1]&255)<<16|(b[p+2]&255)<<8|(b[p+3]&255);}
    private static long le32(byte[] b,int p){return Integer.toUnsignedLong((b[p]&255)|(b[p+1]&255)<<8|(b[p+2]&255)<<16|(b[p+3]&255)<<24);}
    private static int le24(byte[] b,int p){return (b[p]&255)|(b[p+1]&255)<<8|(b[p+2]&255)<<16;}
    private static boolean ascii(byte[] b,int p,String s){for(int i=0;i<s.length();i++)if(b[p+i]!=s.charAt(i))return false;return true;}
    private static final class Input implements AutoCloseable{
        final PushbackInputStream in;final long length;long position;final byte[] scratch=new byte[32768];
        Input(InputStream in,long length){this.in=new PushbackInputStream(in,12);this.length=length;}
        void check()throws IOException{if(Thread.currentThread().isInterrupted())throw new IOException("asset_cancelled");}
        int byteValue()throws IOException{check();int b=in.read();if(b<0)throw invalid();position++;return b;}
        byte[] bytes(int n)throws IOException{check();byte[] b=in.readNBytes(n);position+=b.length;if(b.length!=n)throw invalid();return b;}
        void unread(byte[] b)throws IOException{in.unread(b);position-=b.length;}
        int be16()throws IOException{return byteValue()*256+byteValue();}
        long be32()throws IOException{return ((long)byteValue()<<24)|((long)byteValue()<<16)|(byteValue()<<8)|byteValue();}
        void skip(long n)throws IOException{consume(n,null,0);}
        byte[] consume(long n,CRC32 crc,int prefix)throws IOException{if(n<0||n>length-position)throw invalid();byte[] first=new byte[(int)Math.min(n,prefix)];int captured=0;while(n>0){check();int amount=in.read(scratch,0,(int)Math.min(n,scratch.length));if(amount<1)throw invalid();if(crc!=null)crc.update(scratch,0,amount);int keep=Math.min(amount,first.length-captured);if(keep>0){System.arraycopy(scratch,0,first,captured,keep);captured+=keep;}n-=amount;position+=amount;}return first;}
        public void close()throws IOException{in.close();}
    }
    private AssetImageValidator(){}
}
