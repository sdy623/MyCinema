package local.cinemamanager.seats.fractional;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Bounded, fingerprinted packaged layouts, selected only by an exact per-world contract. */
final class FractionalSeatLayout {
    static final String MODEL = "b4f49860b1c87f9656873a00dfe8591ce182c00f42fb04d8d900759f4dd4ae31";
    private static final String COLLISION_SHA256 = "fbdb253d8181b9ca9def92286d7445a5f9b8325f34f9be3ea476de2c88990ccd";
    private static final String PLAN_SHA256 = "d08f8848dc165c6bcaf0d596c4daf27df7c8355a2daa77afdac2b36a97a44984";
    private static final String ROOT = "/data/mycinema/seat_collision/";
    record Seat(UUID uid, String label, FractionalSeatData.Anchor anchor, Vec3 marker, Vec3 visual, Vec3 click,
                Vec3 exit, FractionalSeatFootprint footprint) {}
    final Map<UUID, Seat> seats;
    final Map<String, Seat> labels;
    final Map<BlockPos, FractionalSeatData> cells;
    final FractionalSeatLayoutVersion.Version version;
    private final Map<BlockPos, JsonObject> hosts;
    private final Map<BlockPos, JsonObject> support;
    private static final Map<FractionalSeatLayoutVersion.Version, FractionalSeatLayout> LOADED = new EnumMap<>(FractionalSeatLayoutVersion.Version.class);
    private record Spec(String plan, String planHash, String collision, String collisionHash, int cells, int contributions) {}
    private static Spec spec(FractionalSeatLayoutVersion.Version version) {
        return switch (version) {
            case ORIGINAL368_V1, ORIGINAL368_CARPET_V2 -> new Spec("original368_highback150_v1-plan.json", Original368Resources.PLAN, "original368_highback150_v1-collision.json.gz", Original368Resources.COLLISION, 1462, 2354);
            case ORIGINAL -> new Spec("selected150-plan.json", PLAN_SHA256, "selected150-layout-collision.json.gz", COLLISION_SHA256, 1814, 3054);
            case Y_SMOOTH_V1, Y_SMOOTH_HIDDEN_PORTS_V1 -> new Spec("selected150-ysmooth-v1-plan.json", "cd3e5f715aca50c32656b680ef4f1429d31be172ac0803c06316295f6dd99645", "selected150-ysmooth-v1-collision.json.gz", "8c7fa8d038755b4bd134028e75fdac0ee07a89ca2d00a6fae423e6bcce0849fe", 1624, 2652);
        };
    }
    private FractionalSeatLayout(FractionalSeatLayoutVersion.Version version, Map<UUID, Seat> seats, Map<BlockPos, FractionalSeatData> cells) {
        this.version = version;
        this.seats = Collections.unmodifiableMap(new LinkedHashMap<>(seats));
        Map<String, Seat> labels = new HashMap<>();
        seats.values().forEach(s -> { if (labels.put(s.label(), s) != null) throw bad("duplicate label"); });
        this.labels = Map.copyOf(labels); this.cells = Map.copyOf(cells);
        this.hosts = isCarpetV2() ? terrain("original368_highback150_carpet_v2-hosts.json", Original368CarpetResources.HOSTS, "hosts", "host", 1462) : isOriginal368() ? terrain("original368_highback150_v1-hosts.json", Original368Resources.HOSTS, "hosts", "host", 1462) : isSmoothed() ? terrain("selected150-ysmooth-v1-hosts.json", "b8a75be88eb29f3d3e8ff3e083041def711bf135943bfe6a3f7fdff203f0a905", "hosts", "host", 1624) : Map.of();
        this.support = isCarpetV2() ? terrain("original368_highback150_carpet_v2-support.json", Original368CarpetResources.SUPPORT, "hosts", "host", Original368CarpetResources.SUPPORT_COUNT) : isOriginal368() ? terrain("original368_highback150_v1-support.json", Original368Resources.SUPPORT, "hosts", "host", Original368Resources.SUPPORT_COUNT) : usesHiddenPorts() ? terrain("selected150-ysmooth-hidden-ports-support.json", "e1fc116f2c0e2b63fee825e1e14a33d848990965ffcb5a4ef04e9f958d92b989", "hosts", "host", 7714) : isSmoothed() ? terrain("selected150-ysmooth-v1-support.json", "4529d309bd3be4449ee9084a9174e7d067630e578912d1036ecc39b0241b8fde", "hosts", "host", 7714) : Map.of();
        if (isSmoothed() && !hosts.keySet().equals(this.cells.keySet())) throw bad("host manifest differs from collision cells");
        if(isCarpetV2()) {
            Original368CarpetContract.validate(this,resource("original368_highback150_carpet_v2-finish.json",Original368CarpetResources.FINISH,false,128*1024));
            Original368Identity.validateCarpetMapping(this,resource("original368_highback150_carpet_v2-identity.json",Original368CarpetResources.IDENTITY,false,2*1024*1024));
        }
    }
    static FractionalSeatLayout get(ServerLevel level) { return get(FractionalSeatLayoutVersion.forLevel(level)); }
    static synchronized FractionalSeatLayout get(FractionalSeatLayoutVersion.Version version) {
        return LOADED.computeIfAbsent(Objects.requireNonNull(version), FractionalSeatLayout::read);
    }
    private static FractionalSeatLayout read(FractionalSeatLayoutVersion.Version version) {
        Spec spec = spec(version);
        JsonObject plan = resource(spec.plan(), spec.planHash(), false, 1024 * 1024);
        if (!MODEL.equals(string(plan, "selected_model_sha256"))) throw bad("selected model fingerprint mismatch");
        Map<UUID, JsonObject> definitions = new LinkedHashMap<>();
        Map<UUID, Map<BlockPos, FractionalSeatData.Contribution>> footprints = new LinkedHashMap<>();
        JsonArray entries = plan.getAsJsonArray("seats");
        int expectedSeats = original368Version(version) ? 368 : 372;
        if (entries == null || entries.size() != expectedSeats) throw bad("planned seat count mismatch");
        if (original368Version(version)) Original368Identity.validate(plan);
        for (JsonElement entry : entries) {
            JsonObject s = entry.getAsJsonObject(); UUID uid = UUID.fromString(string(s, "seat_uid"));
            if (!string(s, "label").matches("[A-L][0-9]{2}") || definitions.put(uid, s) != null) throw bad("invalid seat identity");
            footprints.put(uid, new LinkedHashMap<>());
        }
        Map<BlockPos, FractionalSeatData> cells = readCollision(spec, definitions, footprints);
        Map<UUID, Seat> seats = new LinkedHashMap<>();
        for (var entry : definitions.entrySet()) {
            UUID uid = entry.getKey(); JsonObject s = entry.getValue(); Vec3 a = vector(s, "floor_anchor");
            Vec3 marker = vector(s, "mount_entity_position"), visual = vector(s, "model_entity_position"), click = vector(s, "click_entity_position"), exit = vector(s, "exit_position");
            if (marker.distanceToSqr(a.add(0, .455, 0)) > 1e-14 || click.distanceToSqr(a) > 1e-14
                || visual.distanceToSqr(a.add(0, .5, 0)) > 1e-14 || exit.distanceToSqr(a) > 4
                || footprints.get(uid).isEmpty() || footprints.get(uid).size() > 24) throw bad("invalid authored positions");
            seats.put(uid, new Seat(uid, string(s, "label"), new FractionalSeatData.Anchor(a.x, a.y, a.z, s.get("turns").getAsInt()),
                marker, visual, click, exit, new FractionalSeatFootprint(uid, footprints.get(uid))));
        }
        return new FractionalSeatLayout(version, seats, cells);
    }
    static boolean original368Version(FractionalSeatLayoutVersion.Version version) {return version==FractionalSeatLayoutVersion.Version.ORIGINAL368_V1||version==FractionalSeatLayoutVersion.Version.ORIGINAL368_CARPET_V2;}
    boolean isCarpetV2(){return version==FractionalSeatLayoutVersion.Version.ORIGINAL368_CARPET_V2;}
    boolean isOriginal368() { return original368Version(version); }
    boolean isSmoothed() { return version != FractionalSeatLayoutVersion.Version.ORIGINAL; }
    boolean usesHiddenPorts() { return version == FractionalSeatLayoutVersion.Version.Y_SMOOTH_HIDDEN_PORTS_V1 || isOriginal368(); }
    BlockState expectedHost(BlockPos pos) {
        JsonObject state = hosts.get(pos);
        if (!isSmoothed() || state == null) throw bad("no exact smoothed host at " + pos);
        return state(state);
    }
    Optional<BlockState> supportAt(BlockPos pos) {
        JsonObject state = support.get(pos);
        return state == null ? Optional.empty() : Optional.of(state(state));
    }
    void verifyOriginal368Support(ServerLevel level) {
        if (!isOriginal368()) return;
        for (var entry : support.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) throw bad("unloaded/outside support " + pos);
            BlockState actual = level.getBlockState(pos), required = state(entry.getValue());
            if (actual.is(FractionalSeatModule.CELL.get())) {
                if (!(level.getBlockEntity(pos) instanceof FractionalSeatTile tile) || !tile.hasValidData()
                    || !cells.containsKey(pos)) throw bad("foreign support proxy " + pos);
                actual = tile.host();
            } else if (level.getBlockEntity(pos) != null) throw bad("foreign support block entity " + pos);
            if ((!actual.equals(required) && !Original368SupportFixtures.accepts(pos, required, actual)) || !actual.getFluidState().isEmpty()) throw bad("support changed at " + pos);
        }
    }
    private Map<BlockPos, JsonObject> terrain(String name, String hash, String list, String value, int expectedCount) {
        JsonObject root = resource(name, hash, false, 16 * 1024 * 1024);
        if (!root.keySet().equals(Set.of("plan_sha256", list)) || !spec(version).planHash().equals(string(root, "plan_sha256")))
            throw bad("terrain manifest schema/plan mismatch");
        JsonArray entries = root.getAsJsonArray(list);
        if (entries == null || entries.size() != expectedCount) throw bad("terrain manifest count mismatch");
        Map<BlockPos, JsonObject> result = new HashMap<>();
        for (JsonElement entry : entries) {
            JsonObject cell = entry.getAsJsonObject();
            if (!cell.keySet().equals(Set.of("pos", value))) throw bad("terrain cell schema mismatch");
            Vec3 xyz = vector(cell, "pos"); BlockPos pos = BlockPos.containing(xyz);
            if (xyz.x != pos.getX() || xyz.y != pos.getY() || xyz.z != pos.getZ()) throw bad("fractional terrain cell");
            JsonObject state = cell.getAsJsonObject(value);
            if (state == null || !(state.keySet().equals(Set.of("Name")) || state.keySet().equals(Set.of("Name", "Properties")))
                || !state.get("Name").isJsonPrimitive() || !state.get("Name").getAsJsonPrimitive().isString()) throw bad("terrain state schema mismatch");
            if (result.put(pos, state.deepCopy()) != null) throw bad("duplicate terrain position");
        }
        return Map.copyOf(result);
    }
    static BlockState state(JsonObject data) {
        ResourceLocation id = ResourceLocation.parse(string(data, "Name"));
        var block = BuiltInRegistries.BLOCK.getOptional(id).orElseThrow(() -> bad("unregistered terrain state " + id));
        BlockState result = block.defaultBlockState();
        JsonObject values = data.has("Properties") ? data.getAsJsonObject("Properties") : new JsonObject();
        Set<String> expected = new HashSet<>();
        for (Property<?> property : result.getProperties()) expected.add(property.getName());
        if (!values.keySet().equals(expected)) throw bad("incomplete or unknown terrain properties");
        for (var entry : values.entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) throw bad("invalid terrain property");
            result = property(result, block.getStateDefinition().getProperty(entry.getKey()), entry.getValue().getAsString());
        }
        return result;
    }
    private static <T extends Comparable<T>> BlockState property(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow(() -> bad("invalid terrain property value")));
    }
    private static Map<BlockPos, FractionalSeatData> readCollision(Spec spec, Map<UUID, JsonObject> definitions,
            Map<UUID, Map<BlockPos, FractionalSeatData.Contribution>> footprints) {
        String name = spec.collision();
        Map<BlockPos, FractionalSeatData> cells = new LinkedHashMap<>();
        String schema = null, encoding = null, index = null, model = null;
        int grid = -1, contributions = 0;
        MessageDigest digest = sha256();
        try (InputStream raw = FractionalSeatLayout.class.getResourceAsStream(ROOT + name)) {
            if (raw == null) throw bad("missing packaged resource " + name);
            try (DigestInputStream hashed = new DigestInputStream(new BoundedStream(raw, 32 * 1024 * 1024), digest);
                 JsonReader reader = new JsonReader(new InputStreamReader(
                    new BoundedStream(new GZIPInputStream(hashed), 64 * 1024 * 1024), StandardCharsets.UTF_8))) {
                reader.setLenient(false);
                Set<String> fields = new HashSet<>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String field = reader.nextName();
                    if (!fields.add(field)) throw bad("duplicate resource field");
                    switch (field) {
                        case "schema" -> schema = reader.nextString();
                        case "grid" -> grid = integer(reader);
                        case "runs" -> encoding = reader.nextString();
                        case "index" -> index = reader.nextString();
                        case "input_sha256" -> {
                            reader.beginObject(); Set<String> inputs = new HashSet<>();
                            while (reader.hasNext()) {
                                String input = reader.nextName();
                                if (!inputs.add(input)) throw bad("duplicate input hash");
                                if (input.equals("selectedmodel.native.json")) model = reader.nextString(); else reader.skipValue();
                            }
                            reader.endObject();
                        }
                        case "cells" -> {
                            reader.beginArray();
                            while (reader.hasNext()) {
                                if (cells.size() >= spec.cells()) throw bad("too many cells");
                                Vec3 xyz = null; List<FractionalSeatData.Contribution> owners = null;
                                Set<String> cellFields = new HashSet<>(); reader.beginObject();
                                while (reader.hasNext()) {
                                    String key = reader.nextName();
                                    if (!cellFields.add(key)) throw bad("duplicate cell field");
                                    if (key.equals("pos")) xyz = vector(reader);
                                    else if (key.equals("owners")) {
                                        owners = new ArrayList<>(); reader.beginArray();
                                        while (reader.hasNext()) {
                                            if (owners.size() >= 4) throw bad("owner cap exceeded");
                                            owners.add(owner(reader, definitions));
                                        }
                                        reader.endArray();
                                    } else throw bad("unknown cell field");
                                }
                                reader.endObject();
                                if (xyz == null || owners == null || owners.isEmpty()) throw bad("incomplete cell");
                                BlockPos pos = BlockPos.containing(xyz);
                                if (xyz.x != pos.getX() || xyz.y != pos.getY() || xyz.z != pos.getZ()) throw bad("fractional cell position");
                                FractionalSeatData data = FractionalSeatData.of(owners);
                                if (!data.validAt(pos.getX(), pos.getY(), pos.getZ()) || cells.put(pos, data) != null) throw bad("invalid or duplicate cell");
                                for (var owner : owners) {
                                    if (footprints.get(owner.uid()).put(pos, owner) != null) throw bad("duplicate owner cell");
                                    contributions++;
                                }
                            }
                            reader.endArray();
                        }
                        default -> reader.skipValue(); // Provenance/statistics cannot add geometry or override identity.
                    }
                }
                reader.endObject();
                if (reader.peek() != JsonToken.END_DOCUMENT) throw bad("trailing resource content");
                // Hash every compressed byte, including trailing bytes the gzip decoder did not request.
                byte[] tail = new byte[8192]; while (hashed.read(tail) != -1) {}
            }
        } catch (IOException e) { throw new IllegalStateException("Cannot stream selected collision", e); }
        if (!spec.collisionHash().equals(HexFormat.of().formatHex(digest.digest()))) throw bad("packaged collision byte fingerprint mismatch");
        if (!"fractional-seat-collision-new-v1".equals(schema) || grid != 64 || !"start,endExclusive".equals(encoding)
            || !"(x*64+y)*64+z".equals(index) || !MODEL.equals(model)) throw bad("collision schema or model fingerprint mismatch");
        if (cells.size() != spec.cells() || contributions != spec.contributions()) throw bad("cell/contribution count mismatch");
        return cells;
    }
    private static FractionalSeatData.Contribution owner(JsonReader reader, Map<UUID, JsonObject> definitions) throws IOException {
        UUID uid = null; Vec3 anchor = null; Integer turns = null; int[] runs = null;
        Set<String> fields = new HashSet<>(); reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (!fields.add(field)) throw bad("duplicate owner field");
            switch (field) {
                case "uid" -> uid = UUID.fromString(reader.nextString());
                case "anchor" -> anchor = vector(reader);
                case "turns" -> turns = integer(reader);
                case "runs" -> {
                    int[] buffer = new int[7212]; int count = 0; reader.beginArray();
                    while (reader.hasNext()) {
                        if (count >= buffer.length) throw bad("run count out of bounds");
                        buffer[count++] = integer(reader);
                    }
                    reader.endArray();
                    if (count == 0 || (count & 1) != 0) throw bad("empty or unpaired runs");
                    runs = Arrays.copyOf(buffer, count);
                }
                default -> throw bad("unknown owner field");
            }
        }
        reader.endObject(); JsonObject definition = definitions.get(uid);
        if (definition == null || anchor == null || turns == null || runs == null
            || !anchor.equals(vector(definition, "floor_anchor")) || turns != definition.get("turns").getAsInt()) throw bad("owner identity/anchor mismatch");
        return new FractionalSeatData.Contribution(uid, new FractionalSeatData.Anchor(anchor.x, anchor.y, anchor.z, turns),
            runs, FractionalSeatGeometryProfile.HIGHBACK_150);
    }
    private static int integer(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw bad("integer must be numeric");
        return Integer.parseInt(reader.nextString());
    }
    private static Vec3 vector(JsonReader reader) throws IOException {
        double[] a = new double[3]; reader.beginArray();
        for (int i = 0; i < 3; i++) {
            if (!reader.hasNext() || reader.peek() != JsonToken.NUMBER) throw bad("invalid vector");
            a[i] = reader.nextDouble();
            if (!Double.isFinite(a[i]) || Math.abs(a[i]) > (i == 1 ? 4096 : 30000000)) throw bad("invalid coordinate");
        }
        if (reader.hasNext()) throw bad("oversized vector"); reader.endArray();
        return new Vec3(a[0], a[1], a[2]);
    }
    private static final class BoundedStream extends FilterInputStream {
        private final long limit; private long count;
        BoundedStream(InputStream in, long limit) { super(in); this.limit = limit; }
        @Override public int read() throws IOException { int value = in.read(); if (value >= 0 && ++count > limit) throw new IOException("resource size limit"); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int n = in.read(bytes, offset, (int)Math.min(length, limit - count + 1));
            if (n > 0 && (count += n) > limit) throw new IOException("resource size limit"); return n;
        }
    }
    private static JsonObject resource(String name, String expectedHash, boolean gzip, int limit) {
        try (InputStream raw = FractionalSeatLayout.class.getResourceAsStream(ROOT + name)) {
            if (raw == null) throw bad("missing packaged resource " + name);
            try (InputStream in = gzip ? new GZIPInputStream(raw) : raw) {
                byte[] bytes = in.readNBytes(limit + 1);
                if (bytes.length > limit) throw bad("resource exceeds bounded read");
                if (!expectedHash.equals(HexFormat.of().formatHex(sha256().digest(bytes)))) throw bad("packaged plan byte fingerprint mismatch");
                return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (IOException | JsonParseException e) { throw new IllegalStateException("Invalid selected seat resource: " + name, e); }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }
    private static String string(JsonObject o, String name) { return o.get(name).getAsString(); }
    private static Vec3 vector(JsonObject o, String name) {
        JsonArray a = o.getAsJsonArray(name);
        if (a == null || a.size() != 3) throw bad("invalid vector " + name);
        double x = a.get(0).getAsDouble(), y = a.get(1).getAsDouble(), z = a.get(2).getAsDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x) > 30000000 || Math.abs(z) > 30000000 || Math.abs(y) > 4096) throw bad("invalid coordinate");
        return new Vec3(x, y, z);
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException("Selected chair layout: " + message); }
}
