package local.cinemamanager.seats;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import java.lang.reflect.Type;

/** The builder chooses every route and arrow. Display labels never renumber the seat inventory. */
@JsonAdapter(GuidePreset.Decoder.class)
public record GuidePreset(String hall, String zone, int firstRow, int lastRow, int seat, Arrow arrow,
                          RowScheme rowScheme, RowPrefix rowPrefix, int firstSeat, int lastSeat) {
    public enum Arrow { NONE, LEFT, RIGHT, UP, DOWN, UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT }
    public enum RowScheme { NUMERIC, LETTERS }
    /** LEGACY preserves the exact R-prefixed C&B raster and localized MyCinema text of old saves. */
    public enum RowPrefix { LEGACY, PLAIN, ROW }
    public GuidePreset {
        hall=SeatRegistry.label(hall,32,true);zone=SeatRegistry.label(zone,32,true);
        if(firstRow<1||lastRow<firstRow||lastRow>SeatRegistry.MAX_NUMBER||seat<0||seat>SeatRegistry.MAX_NUMBER||arrow==null||(seat>0&&firstRow!=lastRow))throw SeatRegistry.error("invalid_guide");
        if(firstSeat<0||lastSeat<firstSeat||lastSeat>SeatRegistry.MAX_NUMBER||(firstSeat==0&&lastSeat!=0)||(firstSeat>0&&seat>0))throw SeatRegistry.error("invalid_guide_range");
        if(rowScheme==null)rowScheme=RowScheme.NUMERIC;
        if(rowPrefix==null)rowPrefix=RowPrefix.LEGACY;
        // Letter rows are always bare labels, including older saved/wire presets with R or ROW.
        if(rowScheme==RowScheme.LETTERS)rowPrefix=RowPrefix.PLAIN;
    }
    public GuidePreset(String hall,String zone,int firstRow,int lastRow,int seat,Arrow arrow,RowScheme rowScheme,RowPrefix rowPrefix){
        this(hall,zone,firstRow,lastRow,seat,arrow,rowScheme,rowPrefix,0,0);
    }
    public GuidePreset(String hall,String zone,int firstRow,int lastRow,int seat,Arrow arrow){
        this(hall,zone,firstRow,lastRow,seat,arrow,RowScheme.NUMERIC,RowPrefix.LEGACY);
    }
    public static GuidePreset empty() { return new GuidePreset("","",1,1,0,Arrow.NONE); }
    /** Spreadsheet-style labels: 1=A, 26=Z, 27=AA, 702=ZZ, 703=AAA, 999=ALK. */
    public String rowLabel(int row){
        if(row<1||row>SeatRegistry.MAX_NUMBER)throw SeatRegistry.error("invalid_guide");
        if(rowScheme==RowScheme.NUMERIC)return Integer.toString(row);
        StringBuilder label=new StringBuilder();for(int n=row;n>0;n=(n-1)/26)label.append((char)('A'+(n-1)%26));return label.reverse().toString();
    }
    public String firstLabel(){return rowLabel(firstRow);}
    public String lastLabel(){return rowLabel(lastRow);}
    /** Complete, language-independent display label for previews and generated item names. */
    public boolean hasSeatRange(){return firstSeat>0;}
    public String rowDisplayLabel(){return switch(rowPrefix){case LEGACY->"R";case PLAIN->"";case ROW->"ROW ";}+firstLabel()+(firstRow==lastRow?"":"-"+lastLabel());}
    public String seatRangeLabel(){return hasSeatRange()?"S"+firstSeat+(firstSeat==lastSeat?"":"-"+lastSeat):"";}
    public String displayLabel(){return rowDisplayLabel()+(seat>0?" S"+seat:hasSeatRange()?" · "+seatRangeLabel():"");}
    public String arrowText() { return switch(arrow){case NONE->"";case LEFT->"←";case RIGHT->"→";case UP->"↑";case DOWN->"↓";case UP_LEFT->"↖";case UP_RIGHT->"↗";case DOWN_LEFT->"↙";case DOWN_RIGHT->"↘";}; }

    /** Optional display fields migrate old JSON; unknown values cannot silently select another scheme. */
    public static final class Decoder implements JsonDeserializer<GuidePreset> {
        @Override public GuidePreset deserialize(JsonElement json,Type type,JsonDeserializationContext context){
            JsonObject object=json.getAsJsonObject();
            return new GuidePreset(text(object,"hall"),text(object,"zone"),number(object,"firstRow"),number(object,"lastRow"),number(object,"seat"),Arrow.valueOf(text(object,"arrow")),
                optional(object,"rowScheme",RowScheme.class,RowScheme.NUMERIC),optional(object,"rowPrefix",RowPrefix.class,RowPrefix.LEGACY),optionalNumber(object,"firstSeat"),optionalNumber(object,"lastSeat"));
        }
        private static String text(JsonObject object,String key){JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw SeatRegistry.error("invalid_guide");return value.getAsString();}
        private static int number(JsonObject object,String key){JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw SeatRegistry.error("invalid_guide");try{return value.getAsBigDecimal().intValueExact();}catch(ArithmeticException invalid){throw SeatRegistry.error("invalid_guide");}}
        private static int optionalNumber(JsonObject object,String key){return !object.has(key)||object.get(key).isJsonNull()?0:number(object,key);}
        private static <E extends Enum<E>> E optional(JsonObject object,String key,Class<E> type,E fallback){return !object.has(key)||object.get(key).isJsonNull()?fallback:Enum.valueOf(type,text(object,key));}
    }
}
