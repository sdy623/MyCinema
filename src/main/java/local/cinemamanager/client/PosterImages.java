package local.cinemamanager.client;

import com.mojang.blaze3d.platform.NativeImage;
import local.cinemamanager.media.MediaSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import local.cinemamanager.InfoTile;
import com.mojang.blaze3d.systems.RenderSystem;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Client-only still posters. Call lookup/tick/clear only from the client thread. */
public final class PosterImages {
    public enum Status { PENDING, READY, ERROR }
    public record Result(ResourceLocation texture,int width,int height,Status status,PosterFailure failure,int textureWidth,int textureHeight) {
        public Result(ResourceLocation texture,int width,int height,Status status){this(texture,width,height,status,null,0,0);}
    }
    private static final Result PENDING = new Result(null, 0, 0, Status.PENDING);
    private static final Result ERROR = new Result(null, 0, 0, Status.ERROR);
    static final int MAX_INPUT_BYTES = 64 * 1024 * 1024; // Default only; actual requests use client config.
    static final int MAX_SIDE = 8192;
    static final long MAX_PIXELS = 8192L * 8192;
    static final int MAX_CONTAINER_CHUNKS = 4096;
    private static final int MAX_ENTRIES = 4;
    private static final int MAX_PUBLICATIONS = 16; // Small authority records, not extra images or workers.
    private static final long IDLE_TICKS = 100;
    private static final Map<String, Entry> CACHE = new LinkedHashMap<>(8, .75f, true);
    private static final PosterTexturePool<Result> TEXTURES = new PosterTexturePool<>(MAX_ENTRIES,
            result -> Minecraft.getInstance().getTextureManager().release(result.texture()));
    private static final ThreadPoolExecutor LOADER = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_ENTRIES), task -> {
                Thread thread = new Thread(task, "MyCinema-poster");
                thread.setDaemon(true);
                return thread;
            });
    private static ClientLevel level;
    private static long tick;
    private static long nextTexture;
    private static int activeLoads;

    private PosterImages() {}

    /** The server approves this reference; clients still enforce syntax, format and size bounds. */
    public static Result lookup(InfoTile tile) {
        String approvedUri=tile.publishedPoster;
        Minecraft minecraft = Minecraft.getInstance();
        checkLevel(minecraft);
        if (level == null || approvedUri == null || approvedUri.isBlank()) return ERROR;
        Source source;
        try { source = source(approvedUri); }
        catch (IOException | IllegalArgumentException error) { return failed(new PosterFailure(PosterFailure.Code.INVALID_REFERENCE)); }
        String cacheKey=tile.identity+"|"+tile.publishedPosterToken+"|"+source.key();
        Entry entry = CACHE.get(cacheKey);
        PosterRetryPolicy retryPolicy = null;
        PosterRetryPolicy.Need memoryNeed = null;
        if (entry != null && entry.result.status() == Status.ERROR && entry.retryPolicy.checkDue(tick)
                && entry.retryPolicy.retry(tick, entry.memoryNeed, PosterRetryPolicy.Heap.current(),
                PosterResources.inputBytes(), PosterResources.workBytes())) {
            retryPolicy = entry.retryPolicy;
            memoryNeed = entry.memoryNeed;
            CACHE.remove(cacheKey);
            dispose(entry,"retry_due");
            entry = null;
        }
        if (entry == null) {
            if (CACHE.size() >= MAX_PUBLICATIONS && !evictIdle())
                return failed(new PosterFailure(PosterFailure.Code.UNAVAILABLE));
            PosterTexturePool.Lease<Result> textureLease = TEXTURES.acquire(source.key());
            while (textureLease == null && evictIdle()) textureLease = TEXTURES.acquire(source.key());
            if (textureLease == null) return failed(new PosterFailure(PosterFailure.Code.CAPACITY));
            entry = new Entry(source, minecraft.getResourceManager(), tick,
                    new PosterRedirectClient.Context(level,tile.getBlockPos(),tile.identity,approvedUri,tile.publishedPosterToken),
                    Math.min(PosterResources.textureSide(),RenderSystem.maxSupportedTextureSize()),textureLease);
            if (retryPolicy != null) entry.retryPolicy = retryPolicy;
            entry.memoryNeed = memoryNeed;
            CACHE.put(cacheKey, entry);
            qa(entry,"start","none");
        }
        entry.lastUse = tick;
        publish(entry);
        startLoads();
        if(entry.result.status()==Status.READY){
            Result shared=TEXTURES.get(entry.textureLease,PosterRedirectClient.current(entry.context));
            return shared!=null?shared:failed(new PosterFailure(PosterFailure.Code.PERMISSION));
        }
        return entry.result;
    }

    private static Result failed(PosterFailure failure){return new Result(null,0,0,Status.ERROR,failure,0,0);}
    /** Explicit owner retry touches only the currently saved, server-approved source. */
    public static void retry(InfoTile tile){
        String prefix=tile.identity+"|";
        var it=CACHE.entrySet().iterator();while(it.hasNext()){var entry=it.next();if(entry.getKey().startsWith(prefix)){dispose(entry.getValue(),"explicit_retry");it.remove();}}
    }
    public static String describe(Result result){
        if(result.status()==Status.PENDING)return net.minecraft.client.resources.language.I18n.get("mycinema.panel.poster_loading");
        if(result.status()==Status.READY)return net.minecraft.client.resources.language.I18n.get("mycinema.panel.poster_ready",result.width(),result.height(),result.textureWidth(),result.textureHeight());
        PosterFailure failure=result.failure();if(failure==null)failure=new PosterFailure(PosterFailure.Code.UNAVAILABLE);
        Object[] args=failure.args();String key="mycinema.poster_error."+failure.code().name().toLowerCase(java.util.Locale.ROOT);
        if(failure.code()==PosterFailure.Code.DOWNLOAD_BUDGET&&args.length==3){if(Boolean.TRUE.equals(args[2]))key+="_atleast";args=new Object[]{mib((Number)args[0]),mib((Number)args[1])};}
        else if(failure.code()==PosterFailure.Code.MEMORY_BUDGET&&args.length>=2)args=new Object[]{mib((Number)args[0]),mib((Number)args[1])};
        return net.minecraft.client.resources.language.I18n.get(key,args);
    }
    private static String mib(Number value){return String.format(java.util.Locale.ROOT,"%.1f",value.doubleValue()/1048576.0);}

    /** Invoke every client tick, including while no panels are visible. */
    public static void tick() {
        checkLevel(Minecraft.getInstance());
        PosterRedirectClient.tick();
        tick++;
        Iterator<Entry> entries = CACHE.values().iterator();
        while (entries.hasNext()) {
            Entry entry = entries.next();
            if (!PosterRedirectClient.current(entry.context) || tick - entry.lastUse > IDLE_TICKS) {
                entries.remove();
                dispose(entry,"stale_or_idle");
            } else {
                publish(entry);
                if(entry.result.status()==Status.READY&&entry.finalTarget!=null){
                    if(entry.recheck!=null&&entry.recheck.isDone()){
                        boolean allowed=false;PosterFailure denied=new PosterFailure(PosterFailure.Code.PERMISSION);
                        try{allowed=entry.recheck.getNow(false);}catch(java.util.concurrent.CompletionException e){if(e.getCause() instanceof PosterFailure f)denied=f;}
                        entry.recheck=null;
                        if(!allowed){qa(entry,"failure",denied.code().name());dispose(entry,"authorization_revoked");entry.result=failed(denied);entry.retryPolicy.failed(tick,denied.code());continue;}
                    }
                    if(entry.recheck==null&&tick-entry.lastAuthorization>=40){entry.lastAuthorization=tick;entry.recheck=PosterRedirectClient.request(entry.context,entry.finalTarget);}
                }
            }
        }
        startLoads();
    }

    /** Invoke on level unload/logout and resource reload. Invalidates unfinished work as well. */
    public static void clear() {
        for (Entry entry : CACHE.values()) dispose(entry,"clear");
        CACHE.clear();
        TEXTURES.clear();
        LOADER.purge();
        PosterRedirectClient.clear();
        PanelAssetClient.clear();
        level = null;
    }

    private static void checkLevel(Minecraft minecraft) {
        if (level != minecraft.level) {
            clear();
            level = minecraft.level;
        }
    }

    private static boolean evictIdle() {
        Iterator<Entry> entries = CACHE.values().iterator();
        while (entries.hasNext()) {
            Entry entry = entries.next();
            // Never thrash active textures. Capacity is a visible result, not endless loading.
            if (PosterRedirectClient.current(entry.context) && tick - entry.lastUse <= 1) continue;
            entries.remove();
            dispose(entry,"cache_evict");
            return true;
        }
        return false;
    }

    /** Main-thread admission: at most four unpublished completions, and one per shared source. */
    private static void startLoads() {
        for (Entry entry : CACHE.values()) {
            if (activeLoads >= MAX_ENTRIES) return;
            if (entry.cancelled || entry.work != null || entry.result.status()!=Status.PENDING
                    || !TEXTURES.beginLoad(entry.textureLease,PosterRedirectClient.current(entry.context))) continue;
            entry.ownsLoadSlot=true;
            activeLoads++;
            entry.decodeRequired=TEXTURES.needsDecode(entry.textureLease);
            try { entry.work=LOADER.submit(()->load(entry)); }
            catch (java.util.concurrent.RejectedExecutionException rejected) {
                fail(entry,new PosterFailure(PosterFailure.Code.UNAVAILABLE),"LOADER_REJECTED");
            }
        }
    }

    private static void finishLoad(Entry entry) {
        if (!entry.ownsLoadSlot) return;
        entry.ownsLoadSlot=false;
        activeLoads--;
        TEXTURES.endLoad(entry.textureLease);
    }

    private static void fail(Entry entry,PosterFailure failure,String reason) {
        qa(entry,"failure",reason);
        dispose(entry,"load_failed");
        entry.result=failed(failure);
        entry.retryPolicy.failed(tick,failure.code());
    }

    private static void publish(Entry entry) {
        if(!PosterRedirectClient.current(entry.context)){dispose(entry,"publication_changed");return;}
        var outcome=entry.completion.take();if(outcome==null||entry.cancelled)return;
        finishLoad(entry);
        if (outcome.failure()!=null) {
            entry.failure=outcome.failure();
            fail(entry,entry.failure,entry.failure.code().name());
            return;
        }
        if(!TEXTURES.authorize(entry.textureLease,outcome.value().fingerprint(),PosterRedirectClient.current(entry.context))){
            fail(entry,new PosterFailure(PosterFailure.Code.SOURCE_CHANGED),"CONTENT_CHANGED");return;
        }
        Result shared=TEXTURES.get(entry.textureLease,true);
        if(shared!=null){entry.result=shared;qa(entry,"ready","none");return;}
        Decoded decoded=outcome.value().decoded();
        if(decoded==null){fail(entry,new PosterFailure(PosterFailure.Code.UNAVAILABLE),"SHARED_TEXTURE_MISSING");return;}
        NativeImage image = null;
        DynamicTexture texture = null;
        try {
            image = new NativeImage(decoded.width(), decoded.height(), false);
            for (int y = 0; y < decoded.height(); y++)
                for (int x = 0; x < decoded.width(); x++)
                    image.setPixelRGBA(x, y, decoded.abgr()[y * decoded.width() + x]);
            texture = new DynamicTexture(image);
            image = null; // DynamicTexture now owns the pixels and GL allocation.
            texture.setFilter(true, false);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("mycinema", "poster/" + nextTexture++);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            texture = null; // TextureManager owns the registration; the source pool releases it once.
            Result ready=new Result(id,decoded.sourceWidth(),decoded.sourceHeight(),Status.READY,null,decoded.width(),decoded.height());
            if(!TEXTURES.install(entry.textureLease,ready,PosterRedirectClient.current(entry.context))){
                Minecraft.getInstance().getTextureManager().release(id);
                fail(entry,new PosterFailure(PosterFailure.Code.CANCELLED),"STALE_TEXTURE");return;
            }
            entry.result = ready;
            qa(entry,"ready","none");
        } catch (RuntimeException failure) {
            if (texture != null) texture.close();
            if (image != null) image.close();
            fail(entry,new PosterFailure(PosterFailure.Code.GPU),"GPU");
        }
    }

    private static void dispose(Entry entry,String reason) {
        synchronized (entry) {
            if(!entry.cancelled)qa(entry,"cancel",reason);
            entry.cancelled = true;
            entry.completion.cancel();
        }
        if (entry.work != null) entry.work.cancel(true);
        if (entry.recheck != null) { entry.recheck.cancel(false); entry.recheck=null; }
        finishLoad(entry);
        LOADER.purge();
        // Worker-owned streams close in read's finally block. Never block a render tick closing
        // an HTTP stream: its close/disconnect can wait for a concurrent network read's timeout.
        TEXTURES.release(entry.textureLease);
        entry.result = ERROR;
    }

    private static void load(Entry entry) {
        qa(entry,"load_start","none");
        Loaded loaded = null;
        try {
            if (!entry.cancelled) {
                byte[] bytes = read(entry);
                if (!entry.cancelled && !Thread.currentThread().isInterrupted()) {
                    try { loaded = new Loaded(PosterTexturePool.fingerprint(bytes),entry.decodeRequired?decode(bytes,entry.textureSide):null); }
                    catch (PosterFailure failure) {
                        if (failure.code() == PosterFailure.Code.MEMORY_BUDGET)
                            entry.memoryNeed = new PosterRetryPolicy.Need(bytes.length, ((Number) failure.args()[0]).longValue());
                        throw failure;
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            entry.failure = failure instanceof PosterFailure known ? known
                    : failure instanceof java.nio.file.NoSuchFileException ? new PosterFailure(PosterFailure.Code.LOCAL_MISSING)
                    : failure instanceof java.nio.file.AccessDeniedException || failure instanceof SecurityException ? new PosterFailure(PosterFailure.Code.LOCAL_DENIED)
                    : failure instanceof java.net.SocketTimeoutException ? new PosterFailure(PosterFailure.Code.TIMEOUT)
                    : new PosterFailure(PosterFailure.Code.UNAVAILABLE);
            // A bad poster is a local placeholder, never a player session or a tick failure.
        } finally {
            entry.completion.complete(loaded,loaded==null?entry.failure:null);
        }
    }

    private static byte[] read(Entry entry) throws IOException {
        Source source = entry.source;
        int configured=PosterResources.inputBytes();
        PosterRetryPolicy.Heap heap=PosterRetryPolicy.Heap.current();
        int budget=PosterRetryPolicy.inputBudget(configured,heap.maximum(),heap.available());
        try {
        if(source.resource()==null&&source.uri().getScheme().equals("mycinema-asset"))return PanelAssetClient.read(entry.context,budget,()->entry.cancelled,entry.qaAttempt);
        if(source.resource()==null&&!source.uri().getScheme().equals("file"))
            return PosterHttpTransport.read(source.uri(),(target,control)->{boolean allowed=PosterRedirectClient.authorize(entry.context,target,control);if(allowed)entry.finalTarget=target;return allowed;},()->entry.cancelled,budget);
        if(source.resource()==null&&!Files.readAttributes(Path.of(source.uri()),java.nio.file.attribute.BasicFileAttributes.class).isRegularFile())throw new PosterFailure(PosterFailure.Code.LOCAL_MISSING);
        try(InputStream stream=source.resource()!=null
                ?entry.resources.getResource(source.resource()).orElseThrow(()->new PosterFailure(PosterFailure.Code.LOCAL_MISSING)).open()
                :Files.newInputStream(Path.of(source.uri()))){
            if(entry.cancelled)throw new PosterFailure(PosterFailure.Code.CANCELLED);
            return readBounded(stream,budget,()->entry.cancelled);
        }
        } catch(PosterFailure failure){
            if(failure.code()==PosterFailure.Code.DOWNLOAD_BUDGET&&budget<configured){
                long required=((Number)failure.args()[0]).longValue();
                // A previous decode failure can be followed by a read failure as heap pressure changes.
                entry.memoryNeed=PosterRetryPolicy.readNeed(required,entry.memoryNeed);
                throw new PosterFailure(PosterFailure.Code.MEMORY_BUDGET,required*2,budget*2L);
            }
            if(failure.code()==PosterFailure.Code.MEMORY_BUDGET&&source.resource()==null
                    &&source.uri().getScheme().equals("mycinema-asset"))
                entry.memoryNeed=PosterRetryPolicy.privateAssetReadNeed(failure,entry.memoryNeed);
            throw failure;
        }
    }

    static byte[] readBounded(InputStream stream) throws IOException {return readBounded(stream,PosterResources.inputBytes(),()->false);}
    static byte[] readBounded(InputStream stream,int budget,java.util.function.BooleanSupplier cancelled) throws IOException {
        if(budget<1)throw new IOException("Invalid poster input budget");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        java.util.List<byte[]> chunks=new java.util.ArrayList<>();int total=0;
        while(true){
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)
                throw new PosterFailure(System.nanoTime()>deadline?PosterFailure.Code.TIMEOUT:PosterFailure.Code.CANCELLED);
            byte[] chunk=new byte[Math.min(65536,budget-total+1)];int used=0;
            while(used<chunk.length){int n=stream.read(chunk,used,chunk.length-used);if(n<0)break;used+=n;
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new PosterFailure(System.nanoTime()>deadline?PosterFailure.Code.TIMEOUT:PosterFailure.Code.CANCELLED);}
            total+=used;if(total>budget)throw new PosterFailure(PosterFailure.Code.DOWNLOAD_BUDGET,total,budget,true);
            if(used>0)chunks.add(used==chunk.length?chunk:java.util.Arrays.copyOf(chunk,used));
            if(used<chunk.length){byte[] result=new byte[total];int offset=0;for(byte[] part:chunks){System.arraycopy(part,0,result,offset,part.length);offset+=part.length;}return result;}
        }
    }

    static Source source(String input) throws IOException {
        if (input.length() > 2048 || input.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IOException("Invalid poster reference");
        String value = input.trim();
        if (value.startsWith("resource:")) {
            ResourceLocation id = ResourceLocation.tryParse(value.substring(9));
            if (id == null || id.getPath().isBlank() || id.getPath().startsWith("/")
                    || java.util.Arrays.asList(id.getPath().split("/")).contains(".."))
                throw new IOException("Invalid poster resource");
            return new Source("resource:" + id, null, id);
        }
        String asset=local.cinemamanager.panels.assets.AssetReference.normalize(value);
        if(asset!=null)return new Source(asset,URI.create(asset),null);
        String normalized = MediaSource.normalize(value);
        if (normalized == null) throw new IOException("Invalid poster reference");
        URI uri = MediaSource.relative(normalized) ? Path.of(normalized).toAbsolutePath().normalize().toUri() : URI.create(normalized);
        if (!uri.getScheme().equals("http") && !uri.getScheme().equals("https") && !uri.getScheme().equals("file"))
            throw new IOException("Unsupported poster scheme");
        return new Source(uri.toString(), uri, null);
    }

    /** Decode one image frame only; never instantiates MediaAPI, MRL, a player or an audio engine. */
    static Decoded decode(byte[] bytes) throws IOException {return decode(bytes,PosterResources.textureSide());}
    private static Decoded decode(byte[] bytes,int textureSide) throws IOException {
        if(bytes.length>PosterResources.inputBytes())throw new PosterFailure(PosterFailure.Code.DOWNLOAD_BUDGET,bytes.length,PosterResources.inputBytes(),false);
        var decoded=PosterDecoder.decode(bytes,new PosterDecoder.Limits(textureSide,PosterResources.textureBytes(),PosterResources.workBytes()));
        return new Decoded(decoded.width(),decoded.height(),decoded.sourceWidth(),decoded.sourceHeight(),decoded.abgr());
    }

    /** Preserve pixel/control chunks, without ever parsing or inflating unused image metadata. */
    static byte[] sanitize(byte[] data) throws IOException {
        dimensions(data); // Codec constructors allocate pixels and metadata before next().
        if (u8(data, 0) == 137 && ascii(data, 1, "PNG\r\n\032\n")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
            out.write(data, 0, 8);
            java.util.Set<String> singletons = new java.util.HashSet<>();
            for (int p = 8; p < data.length;) {
                int length = be32(data, p); // dimensions() already checked the unsigned length.
                String type = new String(data, p + 4, 4, StandardCharsets.US_ASCII);
                boolean keep = switch (type) {
                    case "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "bKGD", "gAMA", "sRGB", "cICP", "acTL", "fcTL", "fdAT" -> true;
                    default -> false;
                };
                if (keep) {
                    // The retained non-pixel data is fixed-size and singleton, apart from APNG's
                    // bounded per-frame controls. No tEXt/zTXt/iTXt/iCCP or unknown ancillary
                    // payload ever reaches WATERMedia's metadata maps or decompressors.
                    int limit = switch (type) {
                        case "IHDR" -> 13;
                        case "PLTE" -> 768;
                        case "IEND" -> 0;
                        case "tRNS" -> 256;
                        case "bKGD" -> 6;
                        case "gAMA", "cICP" -> 4;
                        case "sRGB" -> 1;
                        case "acTL" -> 8;
                        case "fcTL" -> 26;
                        default -> MAX_INPUT_BYTES;
                    };
                    if (length > limit) throw new IOException("Invalid PNG rendering chunk");
                    if (!type.equals("IDAT") && !type.equals("fcTL") && !type.equals("fdAT") && !singletons.add(type))
                        throw new IOException("Duplicate PNG rendering chunk");
                    out.write(data, p, length + 12); // Retain the original payload and CRC intact.
                } else if ((u8(data, p + 4) & 32) == 0) {
                    throw new IOException("Unknown critical PNG chunk");
                }
                if (type.equals("IEND")) return out.toByteArray();
                p += length + 12;
            }
            throw new IOException("Missing PNG end chunk");
        }
        if (ascii(data, 0, "RIFF") && ascii(data, 8, "WEBP")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
            out.write(data, 0, 12);
            int end = le32(data, 4) + 8;
            for (int p = 12; p < end;) {
                int length = le32(data, p + 4);
                int total = 8 + length + (length & 1);
                if (!ascii(data, p, "ICCP") && !ascii(data, p, "EXIF") && !ascii(data, p, "XMP ")) {
                    out.write(data, p, total);
                }
                p += total;
            }
            byte[] result = out.toByteArray();
            ByteBuffer.wrap(result).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, result.length - 8);
            if (ascii(result, 12, "VP8X")) result[20] &= ~0x2c; // Clear only ICC/EXIF/XMP feature bits.
            return result;
        }
        // WM JPEGReader skips APP/COM as bounded ByteBuffer views; it does not parse profiles,
        // inflate text or retain metadata. The input and segment-count ceilings bound that path.
        return data;
    }

    static int[] dimensions(byte[] data) throws IOException {
        if (data.length < 12 || data.length > MAX_INPUT_BYTES) throw new IOException("Invalid poster input size");
        if (u8(data, 0) == 137 && ascii(data, 1, "PNG\r\n\032\n")) {
            if (data.length < 33 || be32(data, 8) != 13 || !ascii(data, 12, "IHDR"))
                throw new IOException("Invalid PNG header");
            int[] size = bounds(be32(data, 16), be32(data, 20));
            int chunks = 0;
            for (int p = 8; p < data.length;) {
                if (++chunks > MAX_CONTAINER_CHUNKS) throw new IOException("Too many PNG chunks");
                if (p + 12 > data.length) throw new IOException("Truncated PNG chunk");
                long length = be32(data, p) & 0xffffffffL;
                long next = p + 12L + length;
                if (next > data.length) throw new IOException("Truncated PNG chunk");
                if (ascii(data, p + 4, "IHDR") && p != 8) throw new IOException("Duplicate PNG image header");
                if (ascii(data, p + 4, "fcTL")) {
                    if (length != 26) throw new IOException("Invalid PNG animation frame");
                    bounds(be32(data, p + 12), be32(data, p + 16));
                }
                if (ascii(data, p + 4, "IEND")) break;
                p = (int) next;
            }
            return size;
        }
        if (u8(data, 0) == 255 && u8(data, 1) == 216) return jpegDimensions(data);
        if (ascii(data, 0, "RIFF") && ascii(data, 8, "WEBP")) {
            long end = (le32(data, 4) & 0xffffffffL) + 8;
            if (end > data.length || end < 12) throw new IOException("Invalid WebP container");
            int[] size = webpDimensions(data, 12, (int) end, false, new int[]{0});
            if (size != null) return size;
        }
        throw new IOException("Posters support PNG, JPEG and WebP images");
    }

    private static int[] jpegDimensions(byte[] data) throws IOException {
        int[] size = null;
        int segments = 0;
        for (int p = 2; p < data.length;) {
            if (u8(data, p++) != 255) continue; // Includes entropy-coded scan data.
            while (p < data.length && u8(data, p) == 255) p++;
            if (p >= data.length) break;
            int marker = u8(data, p++);
            if (marker == 0 || marker == 1 || marker >= 208 && marker <= 217) continue;
            if (++segments > MAX_CONTAINER_CHUNKS) throw new IOException("Too many JPEG segments");
            if (p + 2 > data.length) throw new IOException("Truncated JPEG segment");
            int length = u8(data, p) << 8 | u8(data, p + 1);
            if (length < 2 || p + length > data.length) throw new IOException("Truncated JPEG segment");
            if (marker >= 192 && marker <= 207 && marker != 196 && marker != 200 && marker != 204) {
                if (length < 8 || size != null) throw new IOException("Invalid JPEG frame header");
                size = bounds(u8(data, p + 5) << 8 | u8(data, p + 6), u8(data, p + 3) << 8 | u8(data, p + 4));
            }
            p += length;
        }
        if (size == null) throw new IOException("Missing JPEG frame header");
        return size;
    }

    private static int[] webpDimensions(byte[] data, int start, int end, boolean nested, int[] chunks) throws IOException {
        int[] size = null;
        for (int p = start; p < end;) {
            if (++chunks[0] > MAX_CONTAINER_CHUNKS) throw new IOException("Too many WebP chunks");
            if (p + 8 > end) throw new IOException("Truncated WebP chunk");
            long length = le32(data, p + 4) & 0xffffffffL;
            long next = p + 8L + length + (length & 1);
            if (next > end) throw new IOException("Truncated WebP chunk");
            int body = p + 8;
            int[] found = null;
            if (ascii(data, p, "VP8X")) {
                if (nested || length < 10) throw new IOException("Invalid WebP canvas");
                found = bounds(le24(data, body + 4) + 1, le24(data, body + 7) + 1);
            } else if (ascii(data, p, "VP8 ")) {
                if (length < 10 || u8(data, body + 3) != 157 || u8(data, body + 4) != 1 || u8(data, body + 5) != 42)
                    throw new IOException("Invalid WebP frame");
                found = bounds((u8(data, body + 6) | u8(data, body + 7) << 8) & 16383,
                        (u8(data, body + 8) | u8(data, body + 9) << 8) & 16383);
            } else if (ascii(data, p, "VP8L")) {
                if (length < 5 || u8(data, body) != 47) throw new IOException("Invalid WebP frame");
                int packed = le32(data, body + 1);
                found = bounds((packed & 16383) + 1, ((packed >>> 14) & 16383) + 1);
            } else if (ascii(data, p, "ANMF")) {
                if (nested || length < 16) throw new IOException("Invalid WebP animation frame");
                bounds(le24(data, body + 6) + 1, le24(data, body + 9) + 1);
                webpDimensions(data, body + 16, (int) (body + length), true, chunks);
            }
            if (size == null && found != null) size = found;
            p = (int) next;
        }
        return size;
    }

    private static int[] bounds(int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS)
            throw new IOException("Poster dimensions exceed 8192 pixels per side");
        return new int[]{width, height};
    }
    private static boolean ascii(byte[] data, int offset, String text) {
        if (offset < 0 || offset + text.length() > data.length) return false;
        for (int i = 0; i < text.length(); i++) if (u8(data, offset + i) != text.charAt(i)) return false;
        return true;
    }
    private static int u8(byte[] data, int offset) { return data[offset] & 255; }
    private static int le24(byte[] data, int offset) { return u8(data, offset) | u8(data, offset + 1) << 8 | u8(data, offset + 2) << 16; }
    private static int le32(byte[] data, int offset) { return le24(data, offset) | u8(data, offset + 3) << 24; }
    private static int be32(byte[] data, int offset) { return u8(data, offset) << 24 | u8(data, offset + 1) << 16 | u8(data, offset + 2) << 8 | u8(data, offset + 3); }

    record Source(String key, URI uri, ResourceLocation resource) {}
    record Decoded(int width, int height, int sourceWidth, int sourceHeight, int[] abgr) {}
    /** A successful independent read may reuse existing pixels without another decode. */
    private record Loaded(String fingerprint,Decoded decoded) {}
    /** QA-only fixed fields: never log source references, tile identities, publication tokens or exceptions. */
    private static void qa(Entry entry,String event,String failure){
        if(!Boolean.getBoolean("mycinema.qa")||entry.qaAttempt.isEmpty())return;
        String kind=entry.source.resource()!=null?"resource":switch(entry.source.uri().getScheme()){
            case "mycinema-asset"->"private_asset";case "file"->"local_file";case "http","https"->"network";default->"other";};
        try{org.slf4j.LoggerFactory.getLogger("MyCinemaPosterQA").info(
                "poster event={} source_kind={} attempt={} elapsed_ms={} start_tick={} failure={} texture={}",
                event,kind,entry.qaAttempt,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-entry.qaStarted),entry.qaStartTick,failure,
                "ready".equals(event)&&entry.result.texture()!=null?entry.result.texture().toString():"none");
        }catch(RuntimeException ignored){/* Diagnostics must not change image loading or disposal. */}
    }
    private static final class Entry {
        final Source source;
        final ResourceManager resources;
        final PosterRedirectClient.Context context;
        final PosterTexturePool.Lease<Result> textureLease;
        final int textureSide;
        final String qaAttempt;
        final long qaStarted,qaStartTick;
        PosterFailure failure=new PosterFailure(PosterFailure.Code.UNAVAILABLE);
        volatile URI finalTarget;
        java.util.concurrent.CompletableFuture<Boolean> recheck;
        long lastAuthorization;
        volatile boolean cancelled;
        Future<?> work;
        boolean ownsLoadSlot,decodeRequired;
        Result result = PENDING;
        final PosterCompletion<Loaded> completion=new PosterCompletion<>();
        long lastUse;
        PosterRetryPolicy retryPolicy = new PosterRetryPolicy();
        PosterRetryPolicy.Need memoryNeed;
        Entry(Source source, ResourceManager resources, long now,PosterRedirectClient.Context context,int textureSide,PosterTexturePool.Lease<Result> textureLease) {
            boolean qa=Boolean.getBoolean("mycinema.qa");
            this.qaAttempt=qa?java.util.UUID.randomUUID().toString():"";this.qaStarted=qa?System.nanoTime():0;this.qaStartTick=now;
            this.context=context;this.textureSide=textureSide;
            this.textureLease=textureLease;
            this.source = source;
            this.resources = resources;
            this.lastUse = now;
        }
    }
}
