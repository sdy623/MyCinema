package local.cinemamanager.panels;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.Locale;

/** Bounded display-only customization. It never alters a show or a media source. */
public record PanelSettings(Layout layout, String hall, String title, String time, String next,
                            String footer, String poster, int foreground, int background,
                            int accent, int textPercent, int visible, String duration, String endTime,
                            int hiddenHeaders, String screeningFormat, PanelStyle style) {
    public PanelSettings(Layout layout,String hall,String title,String time,String next,String footer,String poster,int foreground,int background,int accent,int textPercent,int visible,String duration,String endTime,int hiddenHeaders){this(layout,hall,title,time,next,footer,poster,foreground,background,accent,textPercent,visible,duration,endTime,hiddenHeaders,"",PanelStyle.DEFAULT);}
    public PanelSettings(Layout layout,String hall,String title,String time,String next,String footer,String poster,int foreground,int background,int accent,int textPercent,int visible,String duration,String endTime){this(layout,hall,title,time,next,footer,poster,foreground,background,accent,textPercent,visible,duration,endTime,0);}
    public PanelSettings(Layout layout,String hall,String title,String time,String next,String footer,String poster,int foreground,int background,int accent,int textPercent,int visible,String duration){this(layout,hall,title,time,next,footer,poster,foreground,background,accent,textPercent,visible,duration,"");}
    /** Old constructors and saved worlds preserve their existing fields. */
    public PanelSettings(Layout layout,String hall,String title,String time,String next,String footer,String poster,int foreground,int background,int accent,int textPercent,int visible){this(layout,hall,title,time,next,footer,poster,foreground,background,accent,textPercent,visible,"");}
    public enum Layout { TEXT, POSTER_TOP, POSTER_LEFT }
    public static final int HALL=1,TITLE=2,TIME=4,STATUS=8,NEXT=16,FOOTER=32,DURATION=64,FORMAT=128,PUBLIC=199,ALL=255;
    public static final int HEADER_FIELDS=TITLE|TIME|DURATION|FORMAT;
    public static final PanelSettings DEFAULT = new PanelSettings(Layout.TEXT,"","","","","","",0xe6f5ff,0x101b28,0x66eeee,100,PUBLIC);
    public PanelSettings {
        screeningFormat=bounded(screeningFormat==null?"":screeningFormat,80);if(style==null)style=PanelStyle.DEFAULT;
        if(layout==null)throw new IllegalArgumentException("mycinema.panel.invalid");
        endTime=bounded(endTime==null?"":endTime,5);duration=bounded(duration==null?"":duration,80);hall=bounded(hall,80);title=bounded(title,160);time=bounded(time,120);next=bounded(next,240);footer=bounded(footer,240);poster=bounded(poster,2048);
        if(!endTime.isBlank()&&(!clockTime(time)||!clockTime(endTime)))throw new IllegalArgumentException("mycinema.panel.time_error");
        if(foreground<0||foreground>0xffffff||background<0||background>0xffffff||accent<0||accent>0xffffff||textPercent<60||textPercent>160||(visible&~ALL)!=0||visible<0||(hiddenHeaders&~HEADER_FIELDS)!=0)
            throw new IllegalArgumentException("mycinema.panel.invalid");
    }
    private static String bounded(String s,int max){if(s==null||s.length()>max||s.indexOf('\0')>=0)throw new IllegalArgumentException("mycinema.panel.invalid");return s.strip();}
    private static boolean clockTime(String s){if(s==null||!s.matches("[0-2][0-9]:[0-5][0-9]"))return false;try{java.time.LocalTime.parse(s);return true;}catch(RuntimeException e){return false;}}
    public String manualTime(){if(endTime.isBlank())return time;boolean tomorrow=java.time.LocalTime.parse(endTime).isBefore(java.time.LocalTime.parse(time));return time+"–"+(tomorrow?"{next_day} ":"")+endTime;}
    public boolean shows(int flag){return (visible&flag)!=0;}
    /** Missing saved/JSON flags are zero, retaining every existing field header. */
    public boolean showsHeader(int flag){return (hiddenHeaders&flag)==0;}
    public CompoundTag save(){CompoundTag t=new CompoundTag();t.putInt("version",6);t.putString("screening_format",screeningFormat);t.put("style",style.save());t.putInt("hidden_headers",hiddenHeaders);t.putString("end_time",endTime);t.putString("duration",duration);t.putString("layout",layout.name());t.putString("hall",hall);t.putString("title",title);t.putString("time",time);t.putString("next",next);t.putString("footer",footer);t.putString("poster",poster);t.putInt("foreground",foreground);t.putInt("background",background);t.putInt("accent",accent);t.putInt("text_percent",textPercent);t.putInt("visible",visible);return t;}
    public static PanelSettings read(CompoundTag t){
        if(t.contains("screening_format")&&!t.contains("screening_format",Tag.TAG_STRING))throw new IllegalArgumentException("mycinema.panel.invalid");
        int flags=t.getInt("visible");if(t.getInt("version")<2)flags=(flags&7)|DURATION;
        if(t.contains("hidden_headers")&&!t.contains("hidden_headers",Tag.TAG_INT))throw new IllegalArgumentException("mycinema.panel.invalid");
        return new PanelSettings(Layout.valueOf(t.getString("layout")),t.getString("hall"),t.getString("title"),t.getString("time"),t.getString("next"),t.getString("footer"),t.getString("poster"),t.getInt("foreground"),t.getInt("background"),t.getInt("accent"),t.getInt("text_percent"),flags,t.getString("duration"),t.getString("end_time"),t.getInt("hidden_headers"),t.getString("screening_format"),readStyle(t));
    }
    private static PanelStyle readStyle(CompoundTag t){if(!t.contains("style"))return PanelStyle.DEFAULT;if(!t.contains("style",Tag.TAG_COMPOUND))throw new IllegalArgumentException("mycinema.panel.invalid");return PanelStyle.read(t.getCompound("style"));}
    /** Explicit public projection; legacy administrator text is never sent to a visitor. */
    public PanelSettings publicOnly(PanelScope scope){boolean hallMode=scope!=PanelScope.LOBBY;return new PanelSettings(layout,hallMode?hall:"",hallMode?title:"",hallMode?time:"","","",PosterPolicy.mayPublish(poster)?poster:"",foreground,background,accent,textPercent,visible&PUBLIC,scope!=PanelScope.LOBBY?duration:"",scope!=PanelScope.LOBBY?endTime:"",hiddenHeaders,hallMode?screeningFormat:"",style);}
    /** Server-approved reference only; this overload does not read client-local WF policy. */
    public PanelSettings publicOnly(PanelScope scope,String approvedPoster){boolean hallMode=scope!=PanelScope.LOBBY;String safe=PosterPolicy.normalize(approvedPoster);if(safe==null||!safe.equals(PosterPolicy.normalize(poster)))safe="";return new PanelSettings(layout,hallMode?hall:"",hallMode?title:"",hallMode?time:"","","",safe,foreground,background,accent,textPercent,visible&PUBLIC,scope!=PanelScope.LOBBY?duration:"",scope!=PanelScope.LOBBY?endTime:"",hiddenHeaders,hallMode?screeningFormat:"",style);}
    public PanelSettings withPoster(String source){return new PanelSettings(layout,hall,title,time,next,footer,source,foreground,background,accent,textPercent,visible,duration,endTime,hiddenHeaders,screeningFormat,style);}
    public PanelSettings withHiddenHeaders(int flags){return new PanelSettings(layout,hall,title,time,next,footer,poster,foreground,background,accent,textPercent,visible,duration,endTime,flags,screeningFormat,style);}
    public PanelSettings withStyle(PanelStyle value){return new PanelSettings(layout,hall,title,time,next,footer,poster,foreground,background,accent,textPercent,visible,duration,endTime,hiddenHeaders,screeningFormat,value);}
    public static PanelSettings savedOrDefault(CompoundTag t){
        try{return read(t);}catch(RuntimeException invalid){
            // A corrupt or newer optional style must not erase the saved poster, headers or display values.
            if(t.contains("style")){var base=t.copy();base.remove("style");try{return read(base);}catch(RuntimeException ignored){}}
            return DEFAULT;
        }
    }
    public static int color(String text){try{String s=text.strip();if(s.startsWith("#"))s=s.substring(1);if(!s.matches("[0-9a-fA-F]{6}"))throw new IllegalArgumentException();return Integer.parseInt(s,16);}catch(RuntimeException e){throw new IllegalArgumentException("mycinema.panel.color_error");}}
    public static String hex(int value){return String.format(Locale.ROOT,"#%06X",value);}
}
