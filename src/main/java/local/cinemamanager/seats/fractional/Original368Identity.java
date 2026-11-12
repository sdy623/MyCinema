package local.cinemamanager.seats.fractional;

import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.world.entity.Entity;

/** Original historical row/column identity. Labels are presentation, never binding keys. */
final class Original368Identity {
    static final String LAYOUT = "original368_highback150_v1";
    static final String LAYOUT_TAG = "o368_layout_" + LAYOUT;
    static final UUID NAMESPACE = UUID.fromString("502cb4c2-d0e5-5ee2-a0f5-3177e3de440a");
    private static final double[] Y = {70,71.5,73,74.5,75.5,77,78.5,79.5,81,82,83,84};
    private static final double[] Z = {21.7,24.7,27.7,30.7,33.7,36.7,41.7,44.7,47.7,50.7,53.7,56.7};
    static UUID seatUid(int row, int column) {
        return uuid5(NAMESPACE, String.format(Locale.ROOT,"r%02d:c%02d",row,column));
    }
    static UUID entityUid(UUID seat, String role) { return uuid5(NAMESPACE, seat + "/" + role); }
    static String uidTag(UUID uid) { return "o368_uid_" + uid; }
    static String labelTag(String label) { return "o368_label_" + label; }
    static Set<String> tags(FractionalSeatLayout.Seat seat, String role) {
        return tags(seat,role,FractionalSeatLayoutVersion.Version.ORIGINAL368_V1);
    }
    static String layoutTag(FractionalSeatLayoutVersion.Version version) {
        if(version!=FractionalSeatLayoutVersion.Version.ORIGINAL368_V1&&version!=FractionalSeatLayoutVersion.Version.ORIGINAL368_CARPET_V2)throw bad("unsupported identity version");
        return "o368_layout_"+version.id;
    }
    static Set<String> tags(FractionalSeatLayout.Seat seat,String role,FractionalSeatLayoutVersion.Version version) {
        if(!Set.of("mount","click","visual").contains(role))throw bad("unknown entity role");
        return Set.of(layoutTag(version),uidTag(seat.uid()),labelTag(seat.label()),"o368_"+role);
    }
    static boolean matches(Entity entity,FractionalSeatLayout.Seat seat,String role,FractionalSeatLayoutVersion.Version version) {
        return matches(entity.getUUID(),entity.getTags(),seat,role,version);
    }
    static boolean matches(UUID entityUuid,Set<String> entityTags,FractionalSeatLayout.Seat seat,String role,FractionalSeatLayoutVersion.Version version) {
        if(!entityUuid.equals(entityUid(seat.uid(),role)))return false;
        Set<String> actual=new HashSet<>();for(String tag:entityTags)if(tag.startsWith("o368_"))actual.add(tag);
        return actual.equals(tags(seat,role,version))&&entityTags.stream().noneMatch(t->t.startsWith("os_"));
    }
    static boolean matches(Entity entity, FractionalSeatLayout.Seat seat, String role) {
        return matches(entity.getUUID(),entity.getTags(),seat,role);
    }
    static boolean matches(UUID entityUuid, Set<String> entityTags, FractionalSeatLayout.Seat seat, String role) {
        if (!entityUuid.equals(entityUid(seat.uid(),role))) return false;
        Set<String> actual = new HashSet<>();
        for(String tag:entityTags) if(tag.startsWith("o368_")) actual.add(tag);
        return actual.equals(tags(seat,role)) && entityTags.stream().noneMatch(t->t.startsWith("os_"));
    }
    static Optional<UUID> taggedUid(Entity entity) {return taggedUid(entity,FractionalSeatLayoutVersion.Version.ORIGINAL368_V1);}
    static Optional<UUID> taggedUid(Entity entity,FractionalSeatLayoutVersion.Version version) {
        List<String> tags=entity.getTags().stream().filter(t->t.startsWith("o368_uid_")).toList();
        if(tags.size()!=1 || !entity.getTags().contains(layoutTag(version))) return Optional.empty();
        try { return Optional.of(UUID.fromString(tags.get(0).substring(9))); }
        catch(IllegalArgumentException malformed) { return Optional.empty(); }
    }
    static void validate(JsonObject plan) {
        if(!LAYOUT.equals(plan.get("layout_id").getAsString())
            || !"mycinema-original368-plan-v1".equals(plan.get("schema").getAsString())
            || !NAMESPACE.toString().equals(plan.get("identity_namespace_uuid").getAsString())) throw bad("identity namespace/schema");
        Set<UUID> found=new HashSet<>(); int[] counts=new int[12];
        for(var value:plan.getAsJsonArray("seats")) {
            JsonObject seat=value.getAsJsonObject(); int r=seat.get("historic_row").getAsInt(),c=seat.get("historic_column").getAsInt();
            if(r<1||r>12||c<1||c>31||(r<=2&&(c==1||c==31))) throw bad("historic coordinate");
            UUID uid=seatUid(r,c);
            if(!uid.toString().equals(seat.get("seat_uid").getAsString())||!found.add(uid)) throw bad("historic UID");
            counts[r-1]++;
            String label=String.format(Locale.ROOT,"%c%02d",64+r,c-(r<=2?1:0));
            if(!label.equals(seat.get("label").getAsString())
                || !String.format(Locale.ROOT,"kumo_anchor_r%02d_s%02d",r,c).equals(seat.get("historic_anchor_tag").getAsString())) throw bad("historic label mapping");
            double x=c<=6?14.6+1.16*(c-1):c<=25?23.56+1.16*(c-7):47.6+1.16*(c-26);
            var a=seat.getAsJsonArray("floor_anchor");
            if(Math.abs(a.get(0).getAsDouble()-x)>1e-10||a.get(1).getAsDouble()!=Y[r-1]||a.get(2).getAsDouble()!=Z[r-1]||seat.get("turns").getAsInt()!=0)throw bad("original anchor changed");
            var e=seat.getAsJsonArray("exit_position"); double exitZ=((Z[r-1]-1.2)+Math.floor((Z[r-1]-.525)*64)/64)/2;
            if(Math.abs(e.get(0).getAsDouble()-x)>1e-10||e.get(1).getAsDouble()!=Y[r-1]||Math.abs(e.get(2).getAsDouble()-exitZ)>1e-10)throw bad("exit contract");
            var entities=seat.getAsJsonObject("entity_uuids");
            if(!entities.keySet().equals(Set.of("mount","click","visual"))) throw bad("entity roles");
            for(String role:entities.keySet())if(!entityUid(uid,role).toString().equals(entities.get(role).getAsString()))throw bad("entity UUID");
        }
        if(found.size()!=368)throw bad("seat count");
        for(int i=0;i<12;i++)if(counts[i]!=(i<2?29:31))throw bad("row count");
    }
    static void validateCarpetMapping(FractionalSeatLayout layout,JsonObject root) {
        var from=FractionalSeatLayoutVersion.Version.ORIGINAL368_V1;var to=FractionalSeatLayoutVersion.Version.ORIGINAL368_CARPET_V2;
        if(!layout.isCarpetV2()||!root.keySet().equals(Set.of("plan_sha256","from_layout","to_layout","entities"))
            ||!Original368Resources.PLAN.equals(root.get("plan_sha256").getAsString())
            ||!from.id.equals(root.get("from_layout").getAsString())||!to.id.equals(root.get("to_layout").getAsString()))throw bad("carpet identity mapping schema");
        var entries=root.getAsJsonArray("entities");if(entries==null||entries.size()!=1104)throw bad("carpet role count");
        Set<UUID> seen=new HashSet<>();
        for(var value:entries){
            var e=value.getAsJsonObject();if(!e.keySet().equals(Set.of("seat_uid","role","entity_uuid","from_tags","to_tags")))throw bad("carpet identity fields");
            UUID uid=UUID.fromString(e.get("seat_uid").getAsString()),entity=UUID.fromString(e.get("entity_uuid").getAsString());
            var seat=layout.seats.get(uid);String role=e.get("role").getAsString();
            if(seat==null||!Set.of("mount","click","visual").contains(role)||!entityUid(uid,role).equals(entity)||!seen.add(entity))throw bad("carpet identity mapping/duplicate");
            for(String side:List.of("from_tags","to_tags")){
                var array=e.getAsJsonArray(side);if(array==null||array.size()!=4)throw bad("carpet exact role tag count");
                Set<String> actual=new HashSet<>();for(var tag:array)if(!tag.isJsonPrimitive()||!tag.getAsJsonPrimitive().isString()||!actual.add(tag.getAsString()))throw bad("carpet tag value/duplicate");
                if(!actual.equals(tags(seat,role,side.equals("from_tags")?from:to)))throw bad("cross-version role mapping");
            }
        }
    }
    private static UUID uuid5(UUID namespace,String name) {
        try {
            var bytes=ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits()).array();
            var digest=MessageDigest.getInstance("SHA-1");digest.update(bytes);byte[] hash=digest.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6]=(byte)((hash[6]&0x0f)|0x50);hash[8]=(byte)((hash[8]&0x3f)|0x80);
            var result=ByteBuffer.wrap(hash);return new UUID(result.getLong(),result.getLong());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static IllegalArgumentException bad(String detail){return new IllegalArgumentException("Original368: "+detail);}
    private Original368Identity(){}
}
