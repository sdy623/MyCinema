package local.cinemamanager.panels.assets;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Bounded real PNG bytes -> server profile -> private manifest restart checks; no graphics library required. */
public final class AssetAlphaChecks {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static byte[] chunk(String name,byte[] payload)throws IOException{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);byte[] kind=name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeInt(payload.length);out.write(kind);out.write(payload);CRC32 crc=new CRC32();crc.update(kind);crc.update(payload);out.writeInt((int)crc.getValue());return bytes.toByteArray();
    }
    private static byte[] image(int width,int height,int color,int filter,byte[] samples,byte[] palette,byte[] transparency,boolean animated,boolean interlaced)throws IOException{
        return imageDepth(width,height,color,8,filter,samples,palette,transparency,animated,interlaced);
    }
    private static byte[] imageDepth(int width,int height,int color,int depth,int filter,byte[] samples,byte[] palette,byte[] transparency,boolean animated,boolean interlaced)throws IOException{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        var header=new ByteArrayOutputStream();var h=new DataOutputStream(header);h.writeInt(width);h.writeInt(height);h.write(new byte[]{(byte)depth,(byte)color,0,0,(byte)(interlaced?1:0)});out.write(chunk("IHDR",header.toByteArray()));
        if(palette!=null)out.write(chunk("PLTE",palette));if(transparency!=null)out.write(chunk("tRNS",transparency));
        if(animated){var control=new ByteArrayOutputStream();var a=new DataOutputStream(control);a.writeInt(1);a.writeInt(0);out.write(chunk("acTL",control.toByteArray()));var frame=new ByteArrayOutputStream();var f=new DataOutputStream(frame);f.writeInt(0);f.writeInt(width);f.writeInt(height);f.writeInt(0);f.writeInt(0);f.writeShort(1);f.writeShort(1);f.writeByte(0);f.writeByte(0);out.write(chunk("fcTL",frame.toByteArray()));}
        int channels=switch(color){case 2->3;case 4->2;case 6->4;default->1;},stride=(width*channels*depth+7)/8,bpp=Math.max(1,(channels*depth+7)/8);
        var compressed=new ByteArrayOutputStream();try(var deflate=new DeflaterOutputStream(compressed)){
            for(int y=0;y<height;y++){
                deflate.write(filter);
                for(int i=0;i<stride;i++){
                    int value=samples[y*stride+i]&255,a=i<bpp?0:samples[y*stride+i-bpp]&255,b=y==0?0:samples[(y-1)*stride+i]&255,c=y==0||i<bpp?0:samples[(y-1)*stride+i-bpp]&255;
                    int p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);
                    int predictor=switch(filter){case 0->0;case 1->a;case 2->b;case 3->(a+b)/2;case 4->pa<=pb&&pa<=pc?a:pb<=pc?b:c;default->0;};deflate.write((value-predictor)&255);
                }
            }
        }
        byte[] data=compressed.toByteArray();int half=data.length/2;out.write(chunk("IDAT",Arrays.copyOfRange(data,0,half)));out.write(chunk("IDAT",new byte[0]));out.write(chunk("IDAT",Arrays.copyOfRange(data,half,data.length)));out.write(chunk("IEND",new byte[0]));return bytes.toByteArray();
    }
    private static AssetAlphaMask decode(Path file,byte[] image)throws Exception{Files.write(file,image);return AssetPngAlpha.read(file,AssetImageValidator.validate(file,1024*1024));}
    private static String hash(byte[] bytes){return HexFormat.of().formatHex(AssetStore.sha256().digest(bytes));}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("mycinema-alpha-check-");
        try{
            Path png=root.resolve("input.png");byte[] rgba={12,24,36,(byte)255,0,0,0,0,18,12,13,1,0,0,0,0};
            byte[] picture=null;AssetAlphaMask expected=null;
            for(int filter=0;filter<5;filter++){
                byte[] bytes=image(2,2,6,filter,rgba,null,null,false,false);AssetAlphaMask mask=decode(png,bytes);
                check(mask!=null&&mask.rectangles().size()==1,"all PNG filters decode the same two-row silhouette");
                for(int y=0;y<32;y++)for(int x=0;x<32;x++)check(mask.occupied(x,y)==(x<16),"alpha 1 stays solid, alpha 0 is clear; tiny source pixels cover entire grid footprints");
                if(expected==null){expected=mask;picture=bytes;}else check(expected.equals(mask),"filter choice does not change occupancy");
            }
            check(AssetPngAlpha.read(root.resolve("must-not-be-opened"),new AssetImageValidator.Result("PNG",8192,8192,100))==null,"pixel budget rejects before opening or inflating source");
            Thread.currentThread().interrupt();boolean cancelled=false;
            try{AssetPngAlpha.read(png,new AssetImageValidator.Result("PNG",2,2,picture.length));}catch(InterruptedIOException expectedCancel){cancelled=true;}finally{Thread.interrupted();}
            check(cancelled,"cancelled worker never supplies an unverified profile");
            check(decode(png,image(2,2,6,0,new byte[16],null,null,false,false))==null,"all-transparent input keeps safe rectangle");
            check(decode(png,image(2,2,6,0,rgba,null,null,true,false))==null,"APNG never creates unstable physical collision");
            check(decode(png,image(2,2,6,0,rgba,null,null,false,true))==null,"interlaced source remains supported visually but collision fallback is explicit");
            check(expected.equals(decode(png,image(2,2,4,0,new byte[]{1,(byte)255,1,0,1,1,1,0},null,null,false,false))),"gray-alpha silhouette");
            check(expected.equals(decode(png,image(2,2,0,0,new byte[]{1,0,2,0},null,new byte[]{0,0},false,false))),"grayscale tRNS silhouette");
            check(expected.equals(decode(png,image(2,2,2,0,new byte[]{1,0,0,0,0,0,1,0,0,0,0,0},null,new byte[6],false,false))),"truecolor tRNS silhouette");
            check(expected.equals(decode(png,image(2,2,3,0,new byte[]{1,0,1,0},new byte[]{0,0,0,12,24,36},new byte[]{0,1},false,false))),"indexed palette tRNS silhouette");
            check(decode(png,image(2,2,3,0,new byte[]{2,0,1,0},new byte[]{0,0,0,12,24,36},new byte[]{0,1},false,false))==null,"out-of-palette sample cannot produce a guessed profile");
            for(int depth:new int[]{1,2,4}){
                byte first=(byte)(1<<(8-depth));byte[] packed={first,first};
                check(expected.equals(decode(png,imageDepth(2,2,3,depth,4,packed,new byte[]{0,0,0,12,24,36},new byte[]{0,1},false,false))),"packed indexed alpha supports depth "+depth);
                check(expected.equals(decode(png,imageDepth(2,2,0,depth,1,packed,null,new byte[]{0,0},false,false))),"packed grayscale tRNS supports depth "+depth);
            }
            byte[] rgba16=new byte[32];rgba16[7]=1;rgba16[23]=1;
            check(expected.equals(decode(png,imageDepth(2,2,6,16,4,rgba16,null,null,false,false))),"16-bit nonzero sub-8bit alpha stays physical");
            byte[] gray16={0,0,0,1,0,0,0,0,0,0,0,1,0,0,0,0};
            check(expected.equals(decode(png,imageDepth(2,2,4,16,2,gray16,null,null,false,false))),"16-bit gray-alpha filtering stays correct");
            int[] checkerboard=new int[32];for(int y=0;y<32;y++)checkerboard[y]=y%2==0?0x55555555:0xaaaaaaaa;
            AssetAlphaMask bounded=AssetAlphaMask.ofRows(checkerboard);check(bounded.rectangles().size()<=128,"pathological fragmented images stay within shape budget");
            for(int y=0;y<32;y++)for(int x=0;x<32;x++)if((checkerboard[y]&(1<<x))!=0)check(bounded.occupied(x,y),"budget coarsening never removes visible source pixels");
            int[] copy=expected.rows();copy[0]=0;check(expected.occupied(0,0),"mask data is defensively copied");
            String reference=AssetReference.ofHash(hash(picture));UUID owner=UUID.randomUUID();Path world=root.resolve("private-world");
            try(AssetStore store=new AssetStore(world)){
                var upload=store.begin(picture.length,hash(picture),1024*1024,1024*1024);upload.append(0,picture);var validated=upload.validate(1024*1024);
                check(expected.equals(validated.alpha()),"existing bounded validation worker derives shape from actual uploaded bytes");
                check(store.commit(upload,validated,owner,1024*1024).equals(reference),"shape attached to immutable exact digest reference");
                check(expected.equals(store.metadata(reference).alpha()),"metadata retains approved shape");
                var duplicate=store.begin(picture.length,hash(picture),1024*1024,1024*1024);duplicate.append(0,picture);store.commit(duplicate,duplicate.validate(1024*1024),owner,1024*1024);
                check(expected.equals(store.metadata(reference).alpha()),"duplicate proof preserves profile without server-thread decode");
            }
            try(AssetStore reopened=new AssetStore(world)){check(expected.equals(reopened.metadata(reference).alpha()),"restart restores bounded private shape metadata");check(reopened.authorized(owner,reference),"shape metadata keeps existing author grant semantics");}
            // Old image manifests remain readable and acquire a profile only after a fresh byte proof.
            Path manifest=world.resolve(hash(picture)+".meta");byte[] extended=Files.readAllBytes(manifest);Files.write(manifest,Arrays.copyOf(extended,extended.length-129));
            try(AssetStore legacy=new AssetStore(world)){
                check(legacy.metadata(reference)!=null&&legacy.metadata(reference).alpha()==null,"legacy metadata uses rectangular fallback");
                var upload=legacy.begin(picture.length,hash(picture),1024*1024,1024*1024);upload.append(0,picture);legacy.commit(upload,upload.validate(1024*1024),owner,1024*1024);
                check(expected.equals(legacy.metadata(reference).alpha()),"repeat uploaded byte proof upgrades legacy profile on worker");
            }
            System.out.println("PASS: "+checks+" bounded PNG alpha, filter, conservative shape budget and private store provenance checks.");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
