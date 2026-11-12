package local.cinemamanager.seats.map;

import java.nio.charset.StandardCharsets;
import java.util.*;
import local.cinemamanager.seats.SeatRegistry;

/** Pure serialization boundary: all pages fit before any page becomes visible. */
public final class SeatMapWire {
    public static final int PAGE_SIZE=64,MAX_PAGES=64,MAX_JSON_BYTES=48*1024-256,MAX_SNAPSHOT_BYTES=1024*1024;
    public record Data(String status,boolean configured,String notice,PublicSeatMap.Page page) {
        public Data {
            if(!Set.of("ok","error").contains(status)||notice==null||notice.length()>96||status.equals("ok")!=(page!=null))throw SeatMapGeometry.invalid("wire");
            if(!notice.isEmpty()&&!notice.matches("mycinema\\.seats\\.map\\.[a-z_]+"))throw SeatMapGeometry.invalid("wire");
        }
    }
    public static String notice(Throwable error,String fallback){
        String message=error.getMessage(),prefix="mycinema.seats.map.";
        if("mycinema.seats.invalid_saved".equals(message))return prefix+"invalid_saved";
        Set<String> known=Set.of("permission","offline","hall_missing","selection","assignment_missing","assignment_replaced","layout_scope","invalid_saved","invalid_layout","map_too_large","busy","stale","invalid","import_file","import_failed","layout_size","rate_limited");
        return message!=null&&message.startsWith(prefix)&&known.contains(message.substring(prefix.length()))?message:prefix+fallback;
    }
    public static int bytes(String raw){return raw.getBytes(StandardCharsets.UTF_8).length;}
    public static String encode(Data data){String raw=SeatRegistry.JSON.toJson(data);if(bytes(raw)>MAX_JSON_BYTES)throw SeatMapGeometry.invalid("map_too_large");return raw;}
    public static Data decode(String raw){
        if(raw==null||raw.length()>MAX_JSON_BYTES||bytes(raw)>MAX_JSON_BYTES)throw SeatMapGeometry.invalid("wire");
        Data data=SeatRegistry.JSON.fromJson(raw,Data.class);if(data==null)throw SeatMapGeometry.invalid("wire");return data;
    }
    public static List<String> pages(PublicSeatMap.View view,boolean configured){
        List<String> encoded=new ArrayList<>();int total=0;
        for(var page:view.pages(PAGE_SIZE)){
            String raw=encode(new Data("ok",configured,"",page));total+=bytes(raw);
            if(total>MAX_SNAPSHOT_BYTES||encoded.size()>=MAX_PAGES)throw SeatMapGeometry.invalid("map_too_large");encoded.add(raw);
        }
        return List.copyOf(encoded);
    }
    private SeatMapWire(){}
}
