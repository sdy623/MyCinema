package local.cinemamanager.panels;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
/** Public date presentation. No seconds, zone IDs, media names, paths or metadata claims. */
public final class PublicTimeRange {
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT),DATE=DateTimeFormatter.ofPattern("MM-dd",Locale.ROOT);
    public record Range(String dayKey,String date,String start,String end,String endDayKey,String endDate){}
    private PublicTimeRange(){}
    public static String dayKey(long timestamp,String zone){try{return Instant.ofEpochMilli(timestamp).atZone(ZoneId.of(zone)).toLocalDate().toString();}catch(RuntimeException e){return "";}}
    public static Range of(long start,long end,long now,String zone){
        try{ZoneId z=ZoneId.of(zone);var a=Instant.ofEpochMilli(start).atZone(z);var n=Instant.ofEpochMilli(now).atZone(z);var b=Instant.ofEpochMilli(end).atZone(z);
            String day=a.toLocalDate().equals(n.toLocalDate())?"today":a.toLocalDate().equals(n.toLocalDate().plusDays(1))?"tomorrow":"date";
            String endDay=end<=start?"unknown":a.toLocalDate().equals(b.toLocalDate())?"same":a.toLocalDate().plusDays(1).equals(b.toLocalDate())?"next_day":"date";
            return new Range(day,DATE.format(a),TIME.format(a),end<=start?"—":TIME.format(b),endDay,DATE.format(b));
        }catch(RuntimeException e){return new Range("unknown","","—","—","unknown","");}
    }
}
