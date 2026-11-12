package local.cinemamanager.seats.map;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import local.cinemamanager.seats.SeatRegistry;
import static local.cinemamanager.seats.map.SeatMapGeometry.*;

/** Explicit authored import format. Assignments reference canonical positions and expected physical UIDs, never nearest-seat guesses. */
public record SeatMapLayoutDocument(int version,SeatMapSnapshot.Scope scope,Layout layout,Transform transform,List<Assignment> assignments) {
    public static final int VERSION=1, MAX_BYTES=1_048_576;
    public record Assignment(long position,String seatUid,String floorId,String zoneId) {
        public Assignment { Position.unpack(position);seatUid=SeatRegistry.uuid(seatUid);floorId=key(floorId);zoneId=key(zoneId); }
    }
    public SeatMapLayoutDocument {
        if(version!=VERSION)throw invalid("layout_version");
        Objects.requireNonNull(scope);Objects.requireNonNull(layout);Objects.requireNonNull(transform);
        if(layout.floors().isEmpty()||!layout.bounds().contains(transform.worldOrigin()))throw invalid("layout");
        assignments=bounded(assignments,SeatRegistry.MAX_SEATS,"assignments");
        Map<String,Floor> floors=new HashMap<>();for(Floor f:layout.floors())floors.put(f.id(),f);
        Map<String,Zone> zones=new HashMap<>();for(Zone z:layout.zones())zones.put(z.id(),z);
        Set<String> ids=new HashSet<>();Set<Long> positions=new HashSet<>();for(Assignment a:assignments){
            unique(ids,a.seatUid());if(!positions.add(a.position()))throw invalid("duplicate_position");checkScope(a.floorId(),a.zoneId(),floors,zones);
            Position p=Position.unpack(a.position());
            if(!layout.bounds().contains(p)||!floors.get(a.floorId()).contains(p)||!zones.get(a.zoneId()).boundary().contains(p.xz()))throw invalid("assignment_bounds");
        }
    }
    public String encode(){String raw=SeatRegistry.JSON.toJson(this);budget(raw);return raw;}
    public static SeatMapLayoutDocument decode(String raw){
        budget(raw);
        try {
            JsonObject root=parseStrict(raw).getAsJsonObject();
            schema(root);
            validateNumbers(root);
            if(root.get("version").getAsBigDecimal().intValueExact()!=VERSION)throw invalid("layout_version");
            SeatMapLayoutDocument result=SeatRegistry.JSON.fromJson(root,SeatMapLayoutDocument.class);
            if(result==null)throw invalid("layout");return result;
        }catch(RuntimeException bad){throw invalid("invalid_layout");}
    }
    private static JsonElement parseStrict(String raw){
        try(JsonReader reader=new JsonReader(new StringReader(raw))){
            reader.setLenient(false);JsonElement value=read(reader,0);
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw invalid("trailing_data");return value;
        }catch(IOException bad){throw invalid("layout_json");}
    }
    private static JsonElement read(JsonReader reader,int depth)throws IOException{
        if(depth>16)throw invalid("layout_depth");
        switch(reader.peek()){
            case BEGIN_OBJECT->{JsonObject object=new JsonObject();reader.beginObject();while(reader.hasNext()){
                String key=reader.nextName();if(object.has(key))throw invalid("duplicate_field");object.add(key,read(reader,depth+1));
            }reader.endObject();return object;}
            case BEGIN_ARRAY->{JsonArray array=new JsonArray();reader.beginArray();while(reader.hasNext())array.add(read(reader,depth+1));reader.endArray();return array;}
            case STRING->{return new JsonPrimitive(reader.nextString());}
            case NUMBER->{return new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));}
            case BOOLEAN->{return new JsonPrimitive(reader.nextBoolean());}
            case NULL->{reader.nextNull();return JsonNull.INSTANCE;}
            default->throw invalid("layout_json");
        }
    }
    private static JsonObject fields(JsonElement value,String names){
        JsonObject object=value.getAsJsonObject();
        if(!object.keySet().equals(Set.of(names.split(" "))))throw invalid("layout_fields");return object;
    }
    private static void points(JsonElement array){for(JsonElement point:array.getAsJsonArray())fields(point,"x z");}
    private static void polygon(JsonElement value){points(fields(value,"vertices").get("vertices"));}
    private static void schema(JsonObject root){
        fields(root,"version scope layout transform assignments");fields(root.get("scope"),"controllerId hallId dimension");
        JsonObject layout=fields(root.get("layout"),"revision bounds floors zones entrances aisles");
        fields(layout.get("bounds"),"minX minY minZ maxX maxY maxZ");
        for(JsonElement f:layout.getAsJsonArray("floors"))fields(f,"id label minY maxY");
        for(JsonElement z:layout.getAsJsonArray("zones"))polygon(fields(z,"id floorId label boundary").get("boundary"));
        for(JsonElement a:layout.getAsJsonArray("aisles"))polygon(fields(a,"id label floorId zoneId boundary").get("boundary"));
        for(JsonElement e:layout.getAsJsonArray("entrances")){
            JsonObject entrance=fields(e,"id label position floorId zoneId seatIds rows");JsonObject position=fields(entrance.get("position"),"x y z");
            for(String key:List.of("x","y","z"))integer(position.get(key));
            for(JsonElement row:entrance.getAsJsonArray("rows"))fields(row,"floorId zoneId row");
        }
        JsonObject transform=fields(root.get("transform"),"worldOrigin orientation scale");fields(transform.get("worldOrigin"),"x z");
        for(JsonElement a:root.getAsJsonArray("assignments")){JsonObject assignment=fields(a,"position seatUid floorId zoneId");JsonElement position=assignment.get("position");if(!position.isJsonPrimitive()||!position.getAsJsonPrimitive().isNumber())throw invalid("number");position.getAsBigDecimal().longValueExact();}
    }
    private static void integer(JsonElement value){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw invalid("number");value.getAsBigDecimal().intValueExact();}
    private static void validateNumbers(JsonElement value){
        if(value.isJsonArray()){for(JsonElement child:value.getAsJsonArray())validateNumbers(child);}
        else if(value.isJsonObject())for(var entry:value.getAsJsonObject().entrySet()){
            JsonElement child=entry.getValue();
            if(Set.of("x","z","minX","maxX","minZ","maxZ","scale").contains(entry.getKey())){
                if(!child.isJsonPrimitive()||!child.getAsJsonPrimitive().isNumber())throw invalid("number");
            }else if(Set.of("version","minY","maxY","row","y").contains(entry.getKey())){
                if(!child.isJsonPrimitive()||!child.getAsJsonPrimitive().isNumber())throw invalid("number");child.getAsBigDecimal().intValueExact();
            }else if((entry.getKey().equals("revision")||entry.getKey().equals("position")&&child.isJsonPrimitive())){
                if(!child.isJsonPrimitive()||!child.getAsJsonPrimitive().isNumber())throw invalid("number");child.getAsBigDecimal().longValueExact();
            }
            validateNumbers(child);
        }
    }
    private static void budget(String raw){if(raw==null||raw.length()>MAX_BYTES||raw.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw invalid("layout_size");}
}
