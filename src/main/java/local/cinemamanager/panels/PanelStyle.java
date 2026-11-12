package local.cinemamanager.panels;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.Map;
import java.util.EnumMap;

/** Display coordinates only. No source, schedule, binding or playback configuration. */
public record PanelStyle(Map<Entry, Placement> entries, int posterPercent) {
    public enum Entry { TEXT, TITLE, TIME, DURATION, FORMAT, HALL, POSTER }
    public enum Horizontal { LEFT, CENTER, RIGHT }
    public enum Vertical { TOP, MIDDLE, BOTTOM }
    public record Placement(Horizontal horizontal, Vertical vertical, int manualX, int manualY, boolean automatic) {
        public Placement {
            if(horizontal==null||vertical==null||manualX < -512||manualX > 512||manualY < -512||manualY > 512)throw invalid();
        }
        public CompoundTag save(){var t=new CompoundTag();t.putString("horizontal",horizontal.name());t.putString("vertical",vertical.name());t.putInt("manual_x",manualX);t.putInt("manual_y",manualY);t.putBoolean("automatic",automatic);return t;}
        public static Placement read(CompoundTag t){
            for(String key:new String[]{"horizontal","vertical"})if(!t.contains(key,Tag.TAG_STRING))throw invalid();
            for(String key:new String[]{"manual_x","manual_y"})if(!t.contains(key,Tag.TAG_INT))throw invalid();
            if(!t.contains("automatic",Tag.TAG_BYTE))throw invalid();
            return new Placement(Horizontal.valueOf(t.getString("horizontal")),Vertical.valueOf(t.getString("vertical")),t.getInt("manual_x"),t.getInt("manual_y"),t.getBoolean("automatic"));
        }
    }
    public static final Placement FLOW=new Placement(Horizontal.LEFT,Vertical.TOP,0,0,true);
    public static final Placement POSTER=new Placement(Horizontal.CENTER,Vertical.MIDDLE,0,0,false);
    public static final PanelStyle DEFAULT=new PanelStyle(Map.of(),0);
    public PanelStyle {
        if(entries==null||entries.entrySet().stream().anyMatch(e->e.getKey()==null||e.getValue()==null)||posterPercent!=0&&(posterPercent<20||posterPercent>85))throw invalid();
        entries=Map.copyOf(entries);
    }
    public Placement placement(Entry entry){return entries.getOrDefault(entry,entry==Entry.POSTER?POSTER:FLOW);}
    public PanelStyle with(Entry entry,Placement placement){var copy=new EnumMap<Entry,Placement>(Entry.class);copy.putAll(entries);if(placement.equals(entry==Entry.POSTER?POSTER:FLOW))copy.remove(entry);else copy.put(entry,placement);return new PanelStyle(copy,posterPercent);}
    public CompoundTag save(){var t=new CompoundTag();t.putInt("version",1);t.putInt("poster_percent",posterPercent);var values=new CompoundTag();entries.forEach((entry,value)->values.put(entry.name(),value.save()));t.put("entries",values);return t;}
    public static PanelStyle read(CompoundTag t){
        if(!t.contains("version",Tag.TAG_INT)||t.getInt("version")!=1)throw invalid();
        if(!t.contains("poster_percent",Tag.TAG_INT)||!t.contains("entries",Tag.TAG_COMPOUND))throw invalid();
        var values=t.getCompound("entries");var result=new EnumMap<Entry,Placement>(Entry.class);
        for(String key:values.getAllKeys()){if(!values.contains(key,Tag.TAG_COMPOUND))throw invalid();result.put(Entry.valueOf(key),Placement.read(values.getCompound(key)));}
        return new PanelStyle(result,t.getInt("poster_percent"));
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("mycinema.panel.invalid");}
}
