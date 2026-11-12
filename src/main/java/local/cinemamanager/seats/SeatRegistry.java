package local.cinemamanager.seats;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import java.util.*;

/** A manually maintained inventory. AVAILABLE describes serviceability, never live occupancy. */
public final class SeatRegistry {
    public static final int MAX_SEATS = 4096, MAX_NUMBER = 999;
    public static final String PHYSICAL_BLOCK = "mycinema:cinema_seat";
    public static final String DOUBLE_SUPPORT_BLOCK = "mycinema:cinema_seat_double_support";
    public static boolean physicalBlock(String id) { return PHYSICAL_BLOCK.equals(id)||DOUBLE_SUPPORT_BLOCK.equals(id); }
    public static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    public enum Status { AVAILABLE, MAINTENANCE, DISABLED }
    /** id identifies this inventory entry; seat_uid identifies the actual, replaceable physical chair. */
    public record Seat(String id, String hall, String zone, int row, int number, long position, Status status,
                       String block, @SerializedName("seat_uid") String seatUid, GuidePreset.RowScheme rowScheme) {
        public Seat {
            if(seatUid==null)seatUid="";
            if(rowScheme==null)rowScheme=GuidePreset.RowScheme.NUMERIC;
        }
        /** Legacy block-only callers do not acquire physical identity by being read or edited. */
        public Seat(String id,String hall,String zone,int row,int number,long position,Status status,String block){
            this(id,hall,zone,row,number,position,status,block,"",GuidePreset.RowScheme.NUMERIC);
        }
        public Seat(String id,String hall,String zone,int row,int number,long position,Status status,String block,String seatUid){
            this(id,hall,zone,row,number,position,status,block,seatUid,GuidePreset.RowScheme.NUMERIC);
        }
        public boolean verifiedIdentity(){return !seatUid.isEmpty();}
        public String rowLabel(){return SeatRegistry.rowLabel(row,rowScheme);}
    }
    public record Count(String hall,String zone,int row,int total,int available,int maintenance,int disabled,GuidePreset.RowScheme rowScheme) {
        public Count {if(rowScheme==null)rowScheme=GuidePreset.RowScheme.NUMERIC;}
        public Count(String hall,String zone,int row,int total,int available,int maintenance,int disabled){
            this(hall,zone,row,total,available,maintenance,disabled,GuidePreset.RowScheme.NUMERIC);
        }
        /** Hall/zone aggregate rows have no row label; mixed display schemes use the numeric identity. */
        public String rowLabel(){return row==0?"":SeatRegistry.rowLabel(row,rowScheme);}
    }
    private LinkedHashMap<String, Seat> seats = new LinkedHashMap<>();
    private long revision;
    public synchronized long revision() { return revision; }
    public synchronized List<Seat> seats() { return List.copyOf(seats.values()); }
    public synchronized Seat find(String id) { Seat seat=seats.get(id); if(seat==null)throw error("missing"); return seat; }
    public synchronized Optional<Seat> at(long position) { return seats.values().stream().filter(s->s.position()==position).findFirst(); }
    public synchronized Optional<Seat> bySeatUid(String seatUid) {
        if(seatUid==null||seatUid.isEmpty())return Optional.empty();
        return seats.values().stream().filter(s->s.seatUid().equals(seatUid)).findFirst();
    }
    public synchronized void requireRevision(long expected) {
        // Long.MAX_VALUE is deliberately not a persistable revision; never create an unreadable save.
        if(expected!=revision || revision>=Long.MAX_VALUE-1)throw error("stale");
    }
    public synchronized Seat put(long expected,String id,String hall,String zone,int row,int number,long position,Status status) {
        return put(expected,id,hall,zone,row,number,position,status,id==null||id.isEmpty()?"":find(id).block());
    }
    public synchronized Seat put(long expected,String id,String hall,String zone,int row,int number,long position,Status status,String block) {
        Seat old=id==null||id.isEmpty()?null:find(id);
        return put(expected,id,hall,zone,row,number,position,status,block,old==null?"":old.seatUid(),old==null?GuidePreset.RowScheme.NUMERIC:old.rowScheme());
    }
    public synchronized Seat put(long expected,String id,String hall,String zone,int row,int number,long position,Status status,String block,String seatUid,GuidePreset.RowScheme rowScheme) {
        requireRevision(expected);
        boolean create=id==null||id.isEmpty();
        if(create&&seats.size()>=MAX_SEATS)throw error("limit");
        Seat old=create?null:find(id);
        Seat seat=validated(new Seat(create?UUID.randomUUID().toString():id,hall,zone,row,number,position,status,block,seatUid,rowScheme));
        if(old!=null&&(!old.seatUid().equals(seat.seatUid())||old.verifiedIdentity()&&old.position()!=seat.position()))throw error("identity_immutable");
        unique(seat,seats.values());
        seats.put(seat.id(),seat);revision++;
        return seat;
    }
    public synchronized void remove(long expected,String id) { requireRevision(expected);find(id);seats.remove(id);revision++; }

    /** Package-private atomic insertion: stage and validate everything before replacing the inventory once. */
    synchronized List<Seat> insertBatch(long expected,List<Seat> additions) {
        requireRevision(expected);
        if(additions==null||additions.isEmpty())throw error("row_empty");
        if(additions.size()>MAX_SEATS||seats.size()>MAX_SEATS-additions.size())throw error("limit");
        LinkedHashMap<String,Seat> staged=new LinkedHashMap<>(seats);
        List<Seat> checked=new ArrayList<>(additions.size());
        for(Seat proposed:additions){
            Seat seat=validated(proposed);
            if(!seat.verifiedIdentity())throw error("row_invalid_uid");
            if(staged.containsKey(seat.id()))throw error("duplicate_id");
            unique(seat,staged.values());staged.put(seat.id(),seat);checked.add(seat);
        }
        List<Seat> result=List.copyOf(checked);
        seats=staged;revision++;
        return result;
    }
    private static void unique(Seat proposed,Collection<Seat> inventory) {
        for(Seat seat:inventory)if(!seat.id().equals(proposed.id())) {
            if(seat.position()==proposed.position())throw error("duplicate_position");
            if(proposed.verifiedIdentity()&&seat.seatUid().equals(proposed.seatUid()))throw error("duplicate_seat_uid");
            if(seat.hall().equals(proposed.hall())&&seat.zone().equalsIgnoreCase(proposed.zone())&&seat.row()==proposed.row()&&seat.number()==proposed.number())throw error("duplicate_number");
        }
    }
    public static Seat validated(Seat s) {
        if(s==null||s.status()==null||s.row()<1||s.row()>MAX_NUMBER||s.number()<1||s.number()>MAX_NUMBER)throw error("invalid");
        String block=s.block()==null?"":s.block();if(block.length()>128||!block.isEmpty()&&!block.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw error("invalid");
        String physical=s.seatUid();
        if(!physical.isEmpty()){
            try{physical=uuid(physical);}catch(IllegalArgumentException invalid){throw error("row_invalid_uid");}
            if(!physicalBlock(block))throw error("row_wrong_block");
        }
        return new Seat(uuid(s.id()),uuid(s.hall()),label(s.zone(),32,false),s.row(),s.number(),s.position(),s.status(),block,physical,s.rowScheme());
    }
    /** Reuse the guide display policy. Numeric identity is never converted or migrated. */
    public static String rowLabel(int row,GuidePreset.RowScheme scheme) {
        return new GuidePreset("","",row,row,0,GuidePreset.Arrow.NONE,scheme,GuidePreset.RowPrefix.PLAIN).rowLabel(row);
    }
    public static String uuid(String value) {
        try { if(value==null||value.length()!=36||!UUID.fromString(value).toString().equals(value))throw error("invalid");return value; }
        catch(RuntimeException invalid) { throw error("invalid"); }
    }
    public static String label(String value,int max,boolean empty) {
        if(value==null)throw error("invalid");String clean=value.strip();
        if((!empty&&clean.isEmpty())||clean.length()>max||clean.codePoints().anyMatch(c->Character.isISOControl(c)||c==0x00a7))throw error("invalid");
        return clean;
    }
    public synchronized List<Count> counts() {
        LinkedHashMap<String,int[]> groups=new LinkedHashMap<>();LinkedHashMap<String,Seat> labels=new LinkedHashMap<>();
        Map<String,GuidePreset.RowScheme> schemes=new HashMap<>();Set<String> mixedSchemes=new HashSet<>();
        for(Seat seat:seats.values())for(int depth=0;depth<3;depth++) {
            String zone=depth==0?"":seat.zone().toLowerCase(Locale.ROOT);int row=depth<2?0:seat.row();String key=seat.hall()+"\u0000"+zone+"\u0000"+row;
            int[] count=groups.computeIfAbsent(key,k->new int[4]);count[0]++;count[seat.status().ordinal()+1]++;
            GuidePreset.RowScheme first=schemes.putIfAbsent(key,seat.rowScheme());if(first!=null&&first!=seat.rowScheme())mixedSchemes.add(key);
            labels.putIfAbsent(key,new Seat("",seat.hall(),depth==0?"":seat.zone(),row,0,0,seat.status(),""));
        }
        List<Count> result=new ArrayList<>();groups.forEach((key,n)->{Seat label=labels.get(key);result.add(new Count(label.hall(),label.zone(),label.row(),n[0],n[1],n[2],n[3],mixedSchemes.contains(key)?GuidePreset.RowScheme.NUMERIC:schemes.get(key)));});return List.copyOf(result);
    }
    public synchronized String encode() {
        JsonObject root=new JsonObject();root.addProperty("schema",2);root.addProperty("revision",revision);root.add("seats",JSON.toJsonTree(seats()));return JSON.toJson(root);
    }
    public static SeatRegistry decode(String raw) {
        try {
            if(raw==null||raw.length()>2_000_000)throw error("invalid");JsonObject root=JsonParser.parseString(raw).getAsJsonObject();
            int schema=number(root,"schema").intValueExact();if(schema!=1&&schema!=2)throw error("invalid");
            SeatRegistry registry=new SeatRegistry();registry.revision=number(root,"revision").longValueExact();
            if(registry.revision<0||registry.revision==Long.MAX_VALUE)throw error("invalid");
            JsonArray entries=root.getAsJsonArray("seats");if(entries.size()>MAX_SEATS)throw error("limit");
            for(JsonElement element:entries){
                JsonObject entry=element.getAsJsonObject();
                Seat seat=validated(new Seat(text(entry,"id"),text(entry,"hall"),text(entry,"zone"),number(entry,"row").intValueExact(),number(entry,"number").intValueExact(),number(entry,"position").longValueExact(),Status.valueOf(text(entry,"status")),
                    entry.has("block")?text(entry,"block"):"",entry.has("seat_uid")?text(entry,"seat_uid"):"",entry.has("rowScheme")?GuidePreset.RowScheme.valueOf(text(entry,"rowScheme")):GuidePreset.RowScheme.NUMERIC));
                if(registry.seats.containsKey(seat.id()))throw error("duplicate_id");unique(seat,registry.seats.values());registry.seats.put(seat.id(),seat);
            }
            return registry;
        } catch(RuntimeException failure) { throw error("invalid_saved"); }
    }
    private static String text(JsonObject object,String key) {
        JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw error("invalid");return value.getAsString();
    }
    private static java.math.BigDecimal number(JsonObject object,String key) {
        JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw error("invalid");return value.getAsBigDecimal();
    }
    public static IllegalArgumentException error(String suffix) { return new IllegalArgumentException("mycinema.seats."+suffix); }
}
