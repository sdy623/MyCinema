package local.cinemamanager.panels.assets;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Actual disk, digest, reservation and streaming-image checks; all artifacts live in new temporary fixtures. */
public final class AssetStoreChecks {
    private static int checks;
    private static final long MIB=1024L*1024;
    public static void main(String[] args)throws Exception{
        Path fixture=Files.createTempDirectory("mycinema-assets-check-");
        try{
            byte[] png=image("png",8,3),other=image("png",9,3);UUID author=UUID.randomUUID(),second=UUID.randomUUID();String hash=hash(png),reference=AssetReference.ofHash(hash);
            check(AssetReference.normalize("  "+reference+" ").equals(reference),"canonical reference accepted");
            for(String invalid:new String[]{"mycinema-asset:../../secret","mycinema-asset:"+"A".repeat(64),reference+"?x=1","https://example.com/a",reference+"/extra","mycinema-asset:"})check(!AssetReference.isAsset(invalid),"opaque reference rejects alternate syntax");
            validator(fixture,png,other);
            interruptedCommit(fixture,png,author);
            failedCleanup(fixture,png);
            Path directory=fixture.resolve("world-one");UUID world;
            try(AssetStore store=new AssetStore(directory)){
                world=store.worldIdentity();AssetStore.Upload upload=store.begin(png.length,hash,MIB,png.length+other.length);
                check(store.reservedBytes()==png.length,"new upload reserves full declared quota");
                rejects(()->upload.append(1,png),"asset_bad_sequence");rejects(()->upload.append(0,new byte[32769]),"asset_bad_sequence");rejects(()->upload.append(0,new byte[0]),"asset_bad_sequence");
                upload.append(0,png);rejects(()->upload.append(1,new byte[]{1}),"asset_bad_sequence");
                String accepted=store.commit(upload,upload.validate(MIB),author,png.length+other.length);
                check(accepted.equals(reference)&&store.usedBytes()==png.length&&store.reservedBytes()==0,"hash supplied only after validated commit");
                check(store.authorized(author,reference)&&!store.authorized(second,reference),"knowledge of a hash gives no author grant");
                try(InputStream in=store.open(reference)){check(Arrays.equals(in.readAllBytes(),png),"stored immutable bytes match original");}
                AssetStore.Upload duplicate=store.begin(png.length,hash,MIB,png.length);check(store.reservedBytes()==0,"duplicate proof works with exactly full world quota and no staging allocation");duplicate.append(0,png);store.commit(duplicate,duplicate.validate(MIB),second,png.length);
                check(store.usedBytes()==png.length&&store.authorized(second,reference),"dedup preserves object and adds proven uploader privately");
                rejects(()->store.begin(other.length,hash(other),MIB,png.length),"asset_world_quota");
                AssetStore.Upload wrong=store.begin(png.length,hash,MIB,png.length);byte[] corrupted=png.clone();corrupted[corrupted.length-1]^=1;wrong.append(0,corrupted);rejects(()->wrong.validate(MIB),"asset_hash_mismatch");store.cancel(wrong);
                check(store.authorized(author,reference)&&store.usedBytes()==png.length,"failed duplicate never damages existing object");
                AssetStore.Upload cancelled=store.begin(other.length,hash(other),MIB,png.length+other.length);cancelled.append(0,other);AssetStore.Validated validated=cancelled.validate(MIB);store.cancel(cancelled);
                rejects(()->store.commit(cancelled,validated,author,2*MIB),"asset_cancelled");check(store.reservedBytes()==0&&!store.exists(AssetReference.ofHash(hash(other))),"stale validated callback cannot commit after cancellation");
                AssetStore.Upload shortFile=store.begin(other.length,hash(other),MIB,png.length+other.length);shortFile.append(0,Arrays.copyOf(other,other.length-1));rejects(()->shortFile.validate(MIB),"asset_bad_length");store.cancel(shortFile);
                rejects(()->store.begin(256*MIB+1,"0".repeat(64),Long.MAX_VALUE,Long.MAX_VALUE),"asset_too_large");
                rejects(()->store.begin(12,"../path",MIB,MIB),"asset_invalid_reference");
                // Recovery removes exact feature-owned temporary names, preserving unknown files and all finals.
                Files.write(directory.resolve("upload-"+UUID.randomUUID()+".part"),new byte[]{1});Files.writeString(directory.resolve("keep-user-note.txt"),"keep");
            }
            try(AssetStore reopened=new AssetStore(directory);AssetStore otherWorld=new AssetStore(fixture.resolve("world-two"))){
                check(reopened.worldIdentity().equals(world)&&reopened.usedBytes()==png.length,"restart preserves own-world identity and quota accounting");
                check(reopened.authorized(author,reference)&&reopened.authorized(second,reference),"restart preserves author manifest");
                check(!otherWorld.exists(reference)&&!otherWorld.authorized(author,reference),"same hash is not authority in another world");
                check(Files.exists(directory.resolve("keep-user-note.txt")),"unknown user files untouched by recovery");
                try(var files=Files.list(directory)){check(files.noneMatch(p->p.getFileName().toString().startsWith("upload-")),"only exact upload staging files removed on restart");}
            }
            // Simultaneous uploads of the same new object reserve space independently then deduplicate at commit.
            try(AssetStore store=new AssetStore(fixture.resolve("concurrent-world"))){var one=store.begin(png.length,hash,MIB,MIB);var two=store.begin(png.length,hash,MIB,MIB);one.append(0,png);two.append(0,png);var a=one.validate(MIB);var b=two.validate(MIB);store.commit(one,a,author,MIB);store.commit(two,b,second,MIB);check(store.usedBytes()==png.length&&store.reservedBytes()==0,"concurrent duplicate commits retain one immutable image");}
            Path occupied=fixture.resolve("occupied");Files.createDirectory(occupied);Files.writeString(occupied.resolve("unrelated"),"private");rejects(()->new AssetStore(occupied),"asset_storage_unavailable");check(Files.exists(occupied.resolve("unrelated")),"foreign directory never adopted or cleared");
            System.out.println("Private asset store checks passed: "+checks);
        }finally{try(var paths=Files.walk(fixture)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void interruptedCommit(Path fixture,byte[] png,UUID author)throws Exception{
        String hash=hash(png),reference=AssetReference.ofHash(hash);Path directory=fixture.resolve("interrupted-world");
        try(AssetStore store=new AssetStore(directory)){
            var upload=store.begin(png.length,hash,MIB,png.length);upload.append(0,png);var validated=upload.validate(MIB);
            Path blockedManifest=directory.resolve(hash+".meta");Files.createDirectory(blockedManifest);
            boolean failed=false;try{store.commit(upload,validated,author,png.length);}catch(IOException expected){failed=true;}
            check(failed,"manifest failure interrupts publication after the immutable move");store.cancel(upload);
            check(store.usedBytes()==png.length&&store.reservedBytes()==0&&!store.authorized(author,reference),"unpublished final stays counted without author authority");
            Files.delete(blockedManifest);
        }
        try(AssetStore store=new AssetStore(directory)){
            check(store.usedBytes()==png.length&&!store.exists(reference),"restart preserves orphan quota and fails publication closed");
            var retry=store.begin(png.length,hash,MIB,png.length);check(store.reservedBytes()==0,"orphan proof needs no second copy when quota is exactly full");
            retry.append(0,png);store.commit(retry,retry.validate(MIB),author,png.length);
            check(store.authorized(author,reference)&&store.usedBytes()==png.length&&store.reservedBytes()==0,"verified orphan adoption restores authority without double counting");
            try(InputStream in=store.open(reference)){check(Arrays.equals(in.readAllBytes(),png),"orphan recovery never replaces immutable bytes");}
        }
        Path corruptDirectory=fixture.resolve("wrong-orphan-world");try(AssetStore ignored=new AssetStore(corruptDirectory)){}
        byte[] wrong=png.clone();
        for(int p=8;p<wrong.length;){int length=java.nio.ByteBuffer.wrap(wrong,p,4).getInt();
            if(new String(wrong,p+4,4,java.nio.charset.StandardCharsets.US_ASCII).equals("IDAT")){
                wrong[p+8+length-1]^=1;var crc=new java.util.zip.CRC32();crc.update(wrong,p+4,length+4);java.nio.ByteBuffer.wrap(wrong,p+8+length,4).putInt((int)crc.getValue());break;
            }p+=length+12;
        }
        Path orphan=corruptDirectory.resolve(hash+".image");Files.write(orphan,wrong);
        check(AssetImageValidator.validate(orphan,MIB).bytes()==png.length,"wrong orphan retains bounded valid container structure");
        try(AssetStore store=new AssetStore(corruptDirectory)){
            var upload=store.begin(png.length,hash,MIB,png.length);upload.append(0,png);rejects(()->upload.validate(MIB),"asset_hash_mismatch");store.cancel(upload);
            check(!store.authorized(author,reference)&&store.usedBytes()==png.length,"uploader proof alone cannot adopt mismatched stored bytes");
            check(Arrays.equals(Files.readAllBytes(orphan),wrong),"mismatched immutable orphan is preserved for administrator recovery");
        }
    }
    private static void failedCleanup(Path fixture,byte[] png)throws Exception{
        Path directory=fixture.resolve("cleanup-world");var blocked=new java.util.concurrent.atomic.AtomicBoolean(true);
        AssetStore.StagingDelete delete=path->{if(blocked.get()&&Files.exists(path))throw new AccessDeniedException(path.toString());Files.deleteIfExists(path);};
        try(AssetStore store=new AssetStore(directory,delete)){
            var upload=store.begin(png.length,hash(png),MIB,png.length);upload.append(0,png);upload.validate(MIB);store.cancel(upload);
            check(store.reservedBytes()==png.length,"failed cancelled staging deletion retains its full reservation");
            rejects(()->store.begin(png.length,hash(png),MIB,png.length),"asset_world_quota");store.retryCleanup();
            check(store.reservedBytes()==png.length,"failed retry cannot release quota");
            blocked.set(false);store.retryCleanup();check(store.reservedBytes()==0,"successful retry releases quota once");store.retryCleanup();check(store.reservedBytes()==0,"repeated cleanup cannot underflow reservations");
        }
        Path leftover=directory.resolve("upload-"+UUID.randomUUID()+".part");Files.write(leftover,png);blocked.set(true);
        try(AssetStore store=new AssetStore(directory,delete)){
            check(store.reservedBytes()==png.length,"restart accounts a staging file it cannot remove");
            rejects(()->store.begin(png.length,hash(png),MIB,png.length),"asset_world_quota");
            blocked.set(false);store.retryCleanup();check(store.reservedBytes()==0&&!Files.exists(leftover),"restart cleanup recovers without deleting immutable objects");
        }
    }
    private static void validator(Path fixture,byte[] png,byte[] other)throws Exception{
        Path input=fixture.resolve("input.bin");Files.write(input,png);var result=AssetImageValidator.validate(input,MIB);check(result.width()==8&&result.height()==3&&result.format().equals("PNG"),"PNG dimensions read from stream");
        for(int[] dimensions:new int[][]{{8192,1},{1,8192},{8192,17}}){Files.write(input,image("png",dimensions[0],dimensions[1]));result=AssetImageValidator.validate(input,MIB);check(result.width()==dimensions[0]&&result.height()==dimensions[1],"per-axis 8192 with arbitrary aspect ratio accepted");}
        Files.write(input,image("png",8193,1));rejects(()->AssetImageValidator.validate(input,MIB),"asset_dimensions");
        try{AssetImageValidator.validate(input,MIB);throw new AssertionError("oversized dimensions accepted");}catch(AssetFailure failure){check(failure.width()==8193&&failure.height()==1&&failure.limit()==8192,"dimension error preserves actual axes and ceiling");}
        IOException detail=AssetFailure.fromWire("asset_dimensions","8193x1/8192");check(detail instanceof AssetFailure failure&&failure.width()==8193&&failure.height()==1&&failure.limit()==8192,"bounded wire dimension detail round trips");
        for(String bad:new String[]{"../private.png","8193x1/99999","08193x1/8192","4294967296x1/8192","-1x1/8192"})check(!(AssetFailure.fromWire("asset_dimensions",bad) instanceof AssetFailure),"untrusted detail never expands the numeric schema");
        check(AssetFailure.fromWire("/private/file.png","8193x1/8192").getMessage().equals("asset_storage_unavailable"),"untrusted failure text is never reflected");
        Files.write(input,image("jpeg",17,9));result=AssetImageValidator.validate(input,MIB);check(result.format().equals("JPEG")&&result.width()==17&&result.height()==9,"real JPEG stream accepted");byte[] jpeg=Files.readAllBytes(input);Files.write(input,Arrays.copyOf(jpeg,jpeg.length-2));rejects(()->AssetImageValidator.validate(input,MIB),"asset_invalid_image");
        for(String b64:new String[]{"UklGRhwAAABXRUJQVlA4TA8AAAAvH8ADAAcQ/Y/+ByKi/wEA","UklGRkwAAABXRUJQVlA4IEAAAABQAwCdASogABAAPjEUiEKiISEYBAAgAwSgB2APwA/AABEQAAD+96KX//16JSql/ew///wsz+Fmfwsz/4TgAAAA","UklGRuQAAABXRUJQVlA4WAoAAAACAAAAHwAADwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAB8AAA8AAMgAAAJWUDhMDwAAAC8fwAMABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAB8AAA8AAMgAAABWUDhMDwAAAC8fwAMAB9D/iP4HIqL/AQBBTk1GKAAAAAAAAAAAAB8AAA8AAMgAAABWUDhMDwAAAC8fwAMABxDR//4HIqL/AQBBTk1GKAAAAAAAAAAAAB8AAA8AAMgAAABWUDhMEAAAAC8fwAMAB9D//kf/AxHR/wA="}){Files.write(input,Base64.getDecoder().decode(b64));result=AssetImageValidator.validate(input,MIB);check(result.format().equals("WebP")&&result.width()==32&&result.height()==16,"real lossless/lossy/animated WebP containers accepted");}
        Files.write(input,"<html>not a picture</html>".getBytes());rejects(()->AssetImageValidator.validate(input,MIB),"asset_unsupported_format");
        for(byte[] invalid:new byte[][]{Arrays.copyOf(png,png.length-1),Arrays.copyOf(png,png.length+1)}){Files.write(input,invalid);rejects(()->AssetImageValidator.validate(input,MIB),"asset_invalid_image");}
        byte[] badCrc=png.clone();badCrc[29]^=1;Files.write(input,badCrc);rejects(()->AssetImageValidator.validate(input,MIB),"asset_invalid_image");
        Files.write(input,png);rejects(()->AssetImageValidator.validate(input,png.length-1),"asset_too_large");
    }
    private static byte[] image(String format,int w,int h)throws IOException{BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,0x123456);var out=new ByteArrayOutputStream();ImageIO.write(image,format,out);image.flush();return out.toByteArray();}
    private static String hash(byte[] bytes){return HexFormat.of().formatHex(AssetStore.sha256().digest(bytes));}
    private interface Action{void run()throws Exception;}
    private static void rejects(Action action,String code)throws Exception{try{action.run();throw new AssertionError("Expected "+code);}catch(IOException expected){check(expected.getMessage().equals(code),"exact failure: "+code+" (was "+expected.getMessage()+")");}}
    private static void check(boolean okay,String reason){checks++;if(!okay)throw new AssertionError(reason);}
}
