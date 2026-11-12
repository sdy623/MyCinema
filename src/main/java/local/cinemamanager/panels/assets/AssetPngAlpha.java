package local.cinemamanager.panels.assets;

import java.io.*;
import java.nio.file.*;
import java.util.zip.InflaterInputStream;

/** Optional bounded streaming decode on the existing validation worker; never opens a URL or client path. */
final class AssetPngAlpha {
    static final long MAX_PIXELS=16L*1024*1024;
    private static final int MAX_SIDE=8192;
    private AssetPngAlpha(){}
    static AssetAlphaMask read(Path file,AssetImageValidator.Result image)throws IOException{
        if(!image.format().equals("PNG")||(long)image.width()*image.height()>MAX_PIXELS)return null;
        try(var input=new DataInputStream(new BufferedInputStream(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS),32768))){
            input.skipNBytes(8);int width=0,height=0,color=-1,channels=0,depth=0,paletteSize=0;
            byte[] transparency=null;boolean supported=true;
            for(int parts=0;parts<4096;parts++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("asset_cancelled");
                int length=input.readInt(),kind=input.readInt();if(length<0||length>image.bytes())return null;
                if(kind==0x49484452){
                    if(length!=13)return null;byte[] h=input.readNBytes(13);if(h.length!=13)return null;
                    width=int32(h,0);height=int32(h,4);color=h[9]&255;depth=h[8]&255;
                    channels=switch(color){case 0,3->1;case 2->3;case 4->2;case 6->4;default->0;};
                    supported=width==image.width()&&height==image.height()&&width>0&&height>0&&width<=MAX_SIDE&&height<=MAX_SIDE
                            &&h[10]==0&&h[11]==0&&h[12]==0&&channels>0
                            &&switch(color){case 0->depth==1||depth==2||depth==4||depth==8||depth==16;case 3->depth==1||depth==2||depth==4||depth==8;default->depth==8||depth==16;};
                }else if(kind==0x6163544c){return null;} // Animated images have no single permanent physical silhouette.
                else if(kind==0x504c5445){paletteSize=length/3;input.skipNBytes(length);}
                else if(kind==0x74524e53){
                    if(transparency!=null||length>256)return null;transparency=input.readNBytes(length);if(transparency.length!=length)return null;
                }else if(kind==0x49444154){
                    if(!supported||color==3&&(paletteSize<1||paletteSize>256))return null;
                    if(transparency!=null&&(color==0&&transparency.length!=2||color==2&&transparency.length!=6
                            ||color==3&&transparency.length>paletteSize||color==4||color==6))return null;
                    IdatStream compressed=new IdatStream(input,length,image.bytes());var inflater=new java.util.zip.Inflater();
                    try(var pixels=new InflaterInputStream(compressed,inflater,32768)){
                        AssetAlphaMask mask=decode(pixels,width,height,color,channels,depth,paletteSize,transparency);
                        return mask!=null&&inflater.finished()&&inflater.getRemaining()==0&&compressed.read()==-1?mask:null;
                    }finally{inflater.end();}
                }else{input.skipNBytes(length);}
                input.readInt();
            }
            return null;
        }catch(InterruptedIOException cancelled){throw cancelled;}
        catch(IOException|IllegalArgumentException malformed){return null;}
    }
    private static AssetAlphaMask decode(InputStream in,int width,int height,int color,int channels,int depth,int paletteSize,byte[] transparency)throws IOException{
        int stride=(width*channels*depth+7)/8,bytesPerPixel=Math.max(1,(channels*depth+7)/8);byte[] row=new byte[stride],prior=new byte[stride];int[] mask=new int[32];
        for(int y=0;y<height;y++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("asset_cancelled");
            int filter=in.read();if(filter<0||filter>4)return null;
            if(in.readNBytes(row,0,stride)!=stride)return null;
            for(int i=0;i<stride;i++){
                int a=i<bytesPerPixel?0:row[i-bytesPerPixel]&255,b=prior[i]&255,c=i<bytesPerPixel?0:prior[i-bytesPerPixel]&255;
                int add=switch(filter){case 0->0;case 1->a;case 2->b;case 3->(a+b)/2;case 4->paeth(a,b,c);default->throw new AssertionError();};
                row[i]=(byte)((row[i]&255)+add);
            }
            int firstY=y*32/height,lastY=((y+1)*32-1)/height;
            for(int x=0;x<width;x++){
                int alpha=255;
                if(color==6)alpha=pixel(row,x,3,channels,depth);else if(color==4)alpha=pixel(row,x,1,channels,depth);
                else if(color==3){int index=pixel(row,x,0,channels,depth);if(index>=paletteSize)return null;alpha=transparency!=null&&index<transparency.length?transparency[index]&255:255;}
                else if(transparency!=null){
                    if(color==0&&sample(transparency,0)==pixel(row,x,0,channels,depth))alpha=0;
                    if(color==2&&sample(transparency,0)==pixel(row,x,0,channels,depth)&&sample(transparency,2)==pixel(row,x,1,channels,depth)&&sample(transparency,4)==pixel(row,x,2,channels,depth))alpha=0;
                }
                if(alpha==0)continue;
                int first=x*32/width,last=((x+1)*32-1)/width;
                int bits=(int)(((1L<<(last-first+1))-1)<<first);
                for(int yy=firstY;yy<=lastY;yy++)mask[yy]|=bits;
            }
            byte[] swap=prior;prior=row;row=swap;
        }
        if(in.read()!=-1)return null;
        try{return AssetAlphaMask.ofRows(mask);}catch(IllegalArgumentException empty){return null;}
    }
    private static int pixel(byte[] row,int x,int channel,int channels,int depth){
        int bit=(x*channels+channel)*depth,index=bit/8;
        return depth==16?sample(row,index):depth==8?row[index]&255:((row[index]&255)>>(8-depth-(bit%8)))&((1<<depth)-1);
    }
    private static int sample(byte[] bytes,int offset){return (bytes[offset]&255)*256+(bytes[offset+1]&255);}
    private static int int32(byte[] b,int at){return (b[at]&255)<<24|(b[at+1]&255)<<16|(b[at+2]&255)<<8|b[at+3]&255;}
    private static int paeth(int a,int b,int c){int p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);return pa<=pb&&pa<=pc?a:pb<=pc?b:c;}
    /** Concatenates only consecutive IDAT payloads; container CRCs were already checked before this decode. */
    private static final class IdatStream extends InputStream{
        private final DataInputStream in;private final long bound;private int remaining,parts;private long read;private boolean end;
        IdatStream(DataInputStream in,int remaining,long bound){this.in=in;this.remaining=remaining;this.bound=bound;}
        private boolean availableChunk()throws IOException{
            while(remaining==0&&!end){
                if(++parts>4096)throw new IOException("asset_invalid_image");in.readInt();int n=in.readInt(),kind=in.readInt();
                if(n<0||n>bound-read)throw new IOException("asset_invalid_image");
                if(kind!=0x49444154){end=true;return false;}remaining=n;
            }
            return !end;
        }
        @Override public int read()throws IOException{if(!availableChunk())return -1;int value=in.read();if(value<0)throw new EOFException();remaining--;read++;return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{if(length==0)return 0;if(!availableChunk())return -1;int count=in.read(bytes,offset,Math.min(length,remaining));if(count<0)throw new EOFException();remaining-=count;read+=count;return count;}
    }
}
