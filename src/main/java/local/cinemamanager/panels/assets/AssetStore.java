package local.cinemamanager.panels.assets;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** One server world's private content-addressed store. Caller serializes begin/commit/cancel. */
public final class AssetStore implements AutoCloseable {
    private static final String MARKER="mycinema-private-panel-assets-v1";
    private static final int MAX_AUTHORS=256,MAX_OBJECTS=4096;
    public record Metadata(String hash,long bytes,String format,int width,int height,Set<UUID> authors,AssetAlphaMask alpha){
        public Metadata(String hash,long bytes,String format,int width,int height,Set<UUID> authors){this(hash,bytes,format,width,height,authors,null);}
        public Metadata{authors=Set.copyOf(authors);}
    }
    public record Validated(String hash,AssetImageValidator.Result image,AssetAlphaMask alpha){
        public Validated(String hash,AssetImageValidator.Result image){this(hash,image,null);}
    }
    private final Path root;private final UUID worldIdentity;
    private final Map<String,Metadata> metadata=new HashMap<>();
    /** Includes immutable images whose manifest commit was interrupted. They still consume quota. */
    private final Map<String,Long> storedImages=new HashMap<>();
    private final Set<Upload> pending=Collections.newSetFromMap(new IdentityHashMap<>());
    private final LinkedHashMap<Path,Long> cleanup=new LinkedHashMap<>();
    @FunctionalInterface interface StagingDelete {void delete(Path path)throws IOException;}
    private final StagingDelete stagingDelete;
    private long used,reserved;private int objectCount;private boolean closed;
    public AssetStore(Path directory)throws IOException{this(directory,Files::deleteIfExists);}
    /** Package-private fault seam: only feature-owned staging cleanup uses this operation. */
    AssetStore(Path directory,StagingDelete stagingDelete)throws IOException{
        this.stagingDelete=Objects.requireNonNull(stagingDelete);
        root=directory.toAbsolutePath().normalize();
        if(Files.exists(root,LinkOption.NOFOLLOW_LINKS)&&(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root)))throw new IOException("asset_storage_unavailable");
        Files.createDirectories(root);
        Path marker=root.resolve("store.identity");
        if(!Files.exists(marker,LinkOption.NOFOLLOW_LINKS)){
            try(var entries=Files.list(root)){if(entries.findAny().isPresent())throw new IOException("asset_storage_unavailable");}
            Files.writeString(marker,MARKER+"\n"+UUID.randomUUID()+"\n",StandardOpenOption.CREATE_NEW);
        }
        if(!regular(marker)||Files.size(marker)>128)throw new IOException("asset_storage_unavailable");
        String[] identity=Files.readString(marker).split("\n");
        try{if(identity.length!=2||!identity[0].equals(MARKER))throw new IllegalArgumentException();worldIdentity=UUID.fromString(identity[1]);}catch(RuntimeException e){throw new IOException("asset_storage_unavailable");}
        try(var paths=Files.newDirectoryStream(root)){
            for(Path file:paths){
                String name=file.getFileName().toString();
                // Only this feature's exact UUID staging patterns are eligible for recovery cleanup.
                if(name.matches("(?:upload|metadata)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part")&&regular(file)){
                    long bytes=Files.size(file);try{stagingDelete.delete(file);}catch(IOException e){cleanup.put(file,bytes);reserved=Math.addExact(reserved,bytes);}
                    if(cleanup.size()+objectCount>MAX_OBJECTS)throw new IOException("asset_storage_unavailable");continue;
                }
                if(name.matches("[0-9a-f]{64}\\.image")&&regular(file)){long bytes=Files.size(file);storedImages.put(name.substring(0,64),bytes);used=Math.addExact(used,bytes);if(++objectCount+cleanup.size()>MAX_OBJECTS)throw new IOException("asset_storage_unavailable");}
            }
        }
        try(var paths=Files.newDirectoryStream(root)){
            for(Path file:paths)if(file.getFileName().toString().matches("[0-9a-f]{64}\\.meta")&&regular(file)){
                try{Metadata item=readMetadata(file);Path image=image(item.hash());if(regular(image)&&Files.size(image)==item.bytes())metadata.put(item.hash(),item);}catch(IOException|RuntimeException ignored){/* Fail closed; keep immutable files for administrator recovery. */}
            }
        }
    }
    public UUID worldIdentity(){return worldIdentity;}
    public synchronized long usedBytes(){return used;}
    public synchronized long reservedBytes(){return reserved;}
    public synchronized Metadata metadata(String reference){return metadata.get(AssetReference.hash(reference));}
    public synchronized boolean exists(String reference){Metadata m=metadata(reference);return m!=null&&regular(image(m.hash()));}
    public synchronized boolean authorized(UUID author,String reference){Metadata m=metadata(reference);return m!=null&&m.authors().contains(author)&&regular(image(m.hash()));}
    public synchronized Upload begin(long bytes,String expectedHash,long imageLimit,long worldLimit)throws IOException{
        if(closed)throw new IOException("asset_cancelled");
        if(cleanup.size()>=MAX_OBJECTS)throw new IOException("asset_storage_unavailable");
        if(bytes<12||bytes>imageLimit||bytes>256L*1024*1024)throw new IOException("asset_too_large");
        if(expectedHash==null||!expectedHash.matches("[0-9a-f]{64}"))throw new IOException("asset_invalid_reference");
        Metadata existing=metadata.get(expectedHash);
        Long stored=storedImages.get(expectedHash);
        boolean duplicate=stored!=null&&stored==bytes&&regular(image(expectedHash))&&Files.size(image(expectedHash))==bytes;
        if(stored!=null&&!duplicate)throw new IOException("asset_storage_unavailable");
        if(!duplicate&&objectCount+pending.size()+cleanup.size()>=MAX_OBJECTS)throw new IOException("asset_world_quota");
        if(!duplicate&&(bytes>worldLimit||used>worldLimit-bytes||reserved>worldLimit-used-bytes))throw new IOException("asset_world_quota");
        Path staging=duplicate?null:root.resolve("upload-"+UUID.randomUUID()+".part");
        Upload upload=new Upload(bytes,expectedHash,staging,duplicate?existing:null,duplicate&&existing==null?image(expectedHash):null);
        if(!duplicate)reserved+=bytes;pending.add(upload);return upload;
    }
    /** Commits only after the server rechecks the live panel and owner on its main thread. */
    public synchronized String commit(Upload upload,Validated validated,UUID author,long worldLimit)throws IOException{
        if(closed||!pending.contains(upload)||upload.cancelled||!upload.finished||!upload.expectedHash.equals(validated.hash()))throw new IOException("asset_cancelled");
        Metadata previous=metadata.get(validated.hash());Set<UUID> authors=new HashSet<>();if(previous!=null)authors.addAll(previous.authors());
        if(!authors.contains(author)&&authors.size()>=MAX_AUTHORS)throw new IOException("asset_author_limit");authors.add(author);
        var imageInfo=validated.image();Metadata next=new Metadata(validated.hash(),upload.expectedBytes,imageInfo.format(),imageInfo.width(),imageInfo.height(),authors,validated.alpha());
        Path target=image(validated.hash());
        if(previous==null&&upload.recovery==null){
            if(upload.staging==null||next.bytes()>worldLimit||used>worldLimit-next.bytes())throw new IOException("asset_world_quota");
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("asset_storage_unavailable");
            // No replacement: immutable content-addressed objects are never overwritten or removed.
            Files.move(upload.staging,target,StandardCopyOption.ATOMIC_MOVE);used+=next.bytes();objectCount++;storedImages.put(next.hash(),next.bytes());
        }
        // An orphan is adopted only after the worker checked its actual digest and container,
        // and the uploader independently supplied every byte with that digest. Never overwrite it.
        if(!regular(target)||Files.size(target)!=next.bytes())throw new IOException("asset_storage_unavailable");
        writeMetadata(next);metadata.put(next.hash(),next);release(upload);return AssetReference.ofHash(next.hash());
    }
    public synchronized InputStream open(String reference)throws IOException{
        Metadata m=metadata(reference);if(closed||m==null)throw new IOException("asset_missing");
        Path path=image(m.hash());if(!regular(path)||Files.size(path)!=m.bytes())throw new IOException("asset_missing");
        return new BufferedInputStream(Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS),32768);
    }
    public synchronized void cancel(Upload upload){if(!pending.contains(upload))return;upload.cancelled=true;try{upload.closeOutput();}catch(IOException ignored){}release(upload);}
    private void release(Upload upload){if(!pending.remove(upload))return;if(upload.staging!=null){try{stagingDelete.delete(upload.staging);reserved-=upload.expectedBytes;}catch(IOException e){cleanup.put(upload.staging,upload.expectedBytes);}}}
    /** Bounded retry, with failed entries rotated so one locked file cannot starve the others. */
    public synchronized void retryCleanup(){
        for(Path path:new ArrayList<>(cleanup.keySet()).stream().limit(4).toList()){
            long bytes=cleanup.remove(path);try{stagingDelete.delete(path);reserved-=bytes;}catch(IOException e){cleanup.put(path,bytes);}
        }
    }
    public synchronized void close(){closed=true;for(Upload upload:List.copyOf(pending))cancel(upload);retryCleanup();}
    private Path image(String hash){return root.resolve(hash+".image");}
    private static boolean regular(Path path){return Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path);}
    private Metadata readMetadata(Path path)throws IOException{
        if(Files.size(path)>16384)throw new IOException("asset_invalid_metadata");
        try(var in=new DataInputStream(Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS))){
            if(!in.readUTF().equals(MARKER)||!in.readUTF().equals(worldIdentity.toString()))throw new IOException("asset_invalid_metadata");
            String hash=in.readUTF();long bytes=in.readLong();String format=in.readUTF();int w=in.readInt(),h=in.readInt(),count=in.readInt();
            if(!hash.matches("[0-9a-f]{64}")||!path.getFileName().toString().equals(hash+".meta")||bytes<12||bytes>256L*1024*1024||w<1||h<1||w>8192||h>8192||!Set.of("PNG","JPEG","WebP").contains(format)||count<1||count>MAX_AUTHORS)throw new IOException("asset_invalid_metadata");
            Set<UUID> authors=new HashSet<>();for(int i=0;i<count;i++)authors.add(new UUID(in.readLong(),in.readLong()));
            AssetAlphaMask alpha=null;int extension=in.read();
            if(extension!=-1){
                if(extension!=1||!format.equals("PNG"))throw new IOException("asset_invalid_metadata");
                int[] rows=new int[AssetAlphaMask.RESOLUTION];for(int i=0;i<rows.length;i++)rows[i]=in.readInt();
                alpha=AssetAlphaMask.ofRows(rows);if(!Arrays.equals(rows,alpha.rows()))throw new IOException("asset_invalid_metadata");
            }
            if(in.read()!=-1||authors.size()!=count)throw new IOException("asset_invalid_metadata");return new Metadata(hash,bytes,format,w,h,authors,alpha);
        }
    }
    private void writeMetadata(Metadata m)throws IOException{
        Path temporary=root.resolve("metadata-"+UUID.randomUUID()+".part");
        try{
            try(var out=new DataOutputStream(Files.newOutputStream(temporary,StandardOpenOption.CREATE_NEW))){out.writeUTF(MARKER);out.writeUTF(worldIdentity.toString());out.writeUTF(m.hash());out.writeLong(m.bytes());out.writeUTF(m.format());out.writeInt(m.width());out.writeInt(m.height());out.writeInt(m.authors().size());for(UUID id:m.authors().stream().sorted().toList()){out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}if(m.alpha()!=null){out.writeByte(1);for(int row:m.alpha().rows())out.writeInt(row);}}
            Files.move(temporary,root.resolve(m.hash()+".meta"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{
            try{stagingDelete.delete(temporary);}catch(IOException e){
                // A failed manifest cleanup also stays visible to quota accounting.
                if(!cleanup.containsKey(temporary)&&regular(temporary)){long bytes=Files.size(temporary);cleanup.put(temporary,bytes);reserved+=bytes;}
            }
        }
    }
    public final class Upload {
        private final long expectedBytes;private final String expectedHash;private final Path staging,recovery;private final Metadata duplicate;
        private final MessageDigest digest;private OutputStream output;private long received;private int sequence;private volatile boolean cancelled,finished,validating;
        private Upload(long bytes,String hash,Path staging,Metadata duplicate,Path recovery)throws IOException{
            expectedBytes=bytes;expectedHash=hash;this.staging=staging;this.duplicate=duplicate;this.recovery=recovery;
            digest=sha256();output=staging==null?OutputStream.nullOutputStream():new BufferedOutputStream(Files.newOutputStream(staging,StandardOpenOption.CREATE_NEW),32768);
        }
        public synchronized long received(){return received;}
        public synchronized int nextSequence(){return sequence;}
        public long expectedBytes(){return expectedBytes;}
        public synchronized void append(int packetSequence,byte[] bytes)throws IOException{
            if(cancelled||finished||validating||output==null)throw new IOException("asset_cancelled");
            if(packetSequence!=sequence||bytes==null||bytes.length<1||bytes.length>32768||bytes.length>expectedBytes-received)throw new IOException("asset_bad_sequence");
            output.write(bytes);digest.update(bytes);received+=bytes.length;sequence++;
        }
        /** Runs on the bounded validation worker. Its result is never itself permission to publish. */
        public Validated validate(long maximum)throws IOException{
            String actual;
            synchronized(this){if(cancelled||finished||validating||received!=expectedBytes)throw new IOException("asset_bad_length");validating=true;closeOutput();actual=HexFormat.of().formatHex(digest.digest());}if(!actual.equals(expectedHash))throw new IOException("asset_hash_mismatch");
            AssetImageValidator.Result result=duplicate==null?AssetImageValidator.validate(recovery==null?staging:recovery,maximum):new AssetImageValidator.Result(duplicate.format(),duplicate.width(),duplicate.height(),duplicate.bytes());
            if(result.bytes()!=expectedBytes)throw new IOException("asset_bad_length");
            if(recovery!=null&&!expectedHash.equals(hashFile(recovery,expectedBytes)))throw new IOException("asset_hash_mismatch");
            // Derive collision only from bytes owned by this world after digest/container validation.
            // Existing manifests without a profile are upgraded by an ordinary repeat upload.
            Path alphaSource=duplicate!=null?image(actual):recovery==null?staging:recovery;
            AssetAlphaMask alpha=duplicate!=null&&duplicate.alpha()!=null?duplicate.alpha():AssetPngAlpha.read(alphaSource,result);
            if(alpha!=null&&(duplicate==null||duplicate.alpha()==null)&&!actual.equals(hashFile(alphaSource,expectedBytes)))throw new IOException("asset_hash_mismatch");
            if(cancelled||Thread.currentThread().isInterrupted())throw new IOException("asset_cancelled");finished=true;return new Validated(actual,result,alpha);
        }
        private synchronized void closeOutput()throws IOException{if(output!=null){OutputStream closing=output;output=null;closing.close();}}
    }
    private static String hashFile(Path file,long expected)throws IOException{
        MessageDigest digest=sha256();long total=0;
        try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[32768];while(true){if(Thread.currentThread().isInterrupted())throw new IOException("asset_cancelled");int n=in.read(buffer);if(n<0)break;if(n>expected-total)throw new IOException("asset_bad_length");digest.update(buffer,0,n);total+=n;}
        }
        if(total!=expected)throw new IOException("asset_bad_length");return HexFormat.of().formatHex(digest.digest());
    }
    public static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
