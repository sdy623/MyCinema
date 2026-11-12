package local.cinemamanager.client;
import local.cinemamanager.panels.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.*;
/** Guest copy shared by the world surface and read-only viewer; wrapping never drops a field. */
public final class GuestPanelText {
    public record Line(FormattedCharSequence text,int color,PanelStyle.Entry entry){public Line(FormattedCharSequence text,int color){this(text,color,PanelStyle.Entry.TEXT);}}
    private GuestPanelText(){}
    public static List<Line> lines(Font font,int width,PanelSettings settings,PanelScope scope,PublicPanelSnapshot snapshot,boolean wall){
        List<Line> out=new ArrayList<>();List<PublicShowRow> rows=snapshot.rows();
        if(rows.isEmpty()){row(out,font,width,settings,scope,snapshot,null,true);return List.copyOf(out);}
        int count=wall&&scope==PanelScope.HALL?1:rows.size();
        for(int i=0;i<count;i++){
            var fields=GuestFields.forRow(settings,scope,snapshot,rows.get(i),scope==PanelScope.HALL&&i==0,k->I18n.get("mycinema.panel."+k));
            if(fields.isEmpty())continue;
            if(!out.isEmpty())out.add(new Line(Component.empty().getVisualOrderText(),settings.foreground()));
            append(out,font,width,settings,fields);
        }
        return List.copyOf(out);
    }
    private static void row(List<Line> out,Font font,int width,PanelSettings settings,PanelScope scope,PublicPanelSnapshot snapshot,PublicShowRow row,boolean overrides){append(out,font,width,settings,GuestFields.forRow(settings,scope,snapshot,row,overrides,k->I18n.get("mycinema.panel."+k)));}
    private static void append(List<Line> out,Font font,int width,PanelSettings settings,List<GuestFields.Field> fields){
        for(var field:fields){Component text=fieldText(settings,field);
            for(var line:font.split(text,Math.max(1,width)))out.add(new Line(line,field.accent()?settings.accent():settings.foreground(),entry(field.label())));}
    }
    public static PanelStyle.Entry entry(String label){return switch(label){case "title_label"->PanelStyle.Entry.TITLE;case "time_label"->PanelStyle.Entry.TIME;case "duration_label","duration_manual_label"->PanelStyle.Entry.DURATION;case "format_label"->PanelStyle.Entry.FORMAT;case "hall_label"->PanelStyle.Entry.HALL;default->PanelStyle.Entry.TEXT;};}
    public static List<PanelLayout.LinePosition> positions(Font font,PanelLayout.Rect area,List<Line> lines,PanelSettings settings){
        return PanelLayout.placeLines(area,lines.stream().map(line->new PanelLayout.LineMeasure(line.entry(),font.width(line.text()))).toList(),font.lineHeight+2,font.lineHeight,settings.style());
    }
    /** Both physical panels and guest viewers use this component before measuring or wrapping. */
    public static Component fieldText(PanelSettings settings,GuestFields.Field field){
        int flag=switch(field.label()){
            case "title_label"->PanelSettings.TITLE;
            case "time_label"->PanelSettings.TIME;
            case "format_label"->PanelSettings.FORMAT;
            case "duration_label","duration_manual_label"->PanelSettings.DURATION;
            default->0;
        };
        if(!settings.showsHeader(flag))return Component.literal(field.value());
        return Component.empty().append(Component.translatable("mycinema.panel."+field.label())).append(": ").append(field.value());
    }
}
