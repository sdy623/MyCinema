package local.cinemamanager.client;

import local.cinemamanager.operations.PublicOperationsSnapshot;
import local.cinemamanager.panels.*;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Wall copy consumes the source-free projection only. The wall never carries command authority. */
public final class OperationsWallText {
    private OperationsWallText(){}
    private static Component tr(String key,Object...args){return Component.translatable("mycinema.operations."+key,args);}
    static String time(PublicTimeRange.Range range){return (range.dayKey().equals("today")?"":range.date()+" ")+range.start()+"–"+(range.endDayKey().equals("same")?"":range.endDate()+" ")+range.end();}
    public static List<GuestPanelText.Line> lines(Font font,int width,PanelSettings settings,PublicOperationsSnapshot data,boolean invalid){
        List<GuestPanelText.Line> lines=new ArrayList<>();
        java.util.function.Consumer<Component> add=text->{for(var line:font.split(text,Math.max(1,width)))lines.add(new GuestPanelText.Line(line,settings.foreground()));};
        add.accept(tr("title"));
        if(invalid){add.accept(tr("invalid_role"));return List.copyOf(lines);}
        add.accept(Component.literal(data.cinema()));
        if(!data.state().equals("AVAILABLE"))add.accept(tr("wall."+data.state().toLowerCase(Locale.ROOT)));
        for(var hall:data.halls()){
            add.accept(tr("hall_status",hall.name(),tr("status."+hall.status().toLowerCase(Locale.ROOT)).getString()));
            if(hall.clips()>0)add.accept(tr("playlist",hall.playlist(),hall.clip(),hall.clips()));
            else add.accept(tr("no_programme"));
        }
        if(!data.timezone().isBlank()){
            add.accept(tr("today",PublicTimeRange.dayKey(data.generatedAt(),data.timezone())));
            if(data.plans().isEmpty())add.accept(tr("no_shows"));
            for(var plan:data.plans()){
                var time=PublicTimeRange.of(plan.start(),plan.end(),data.generatedAt(),data.timezone());
                add.accept(Component.literal(plan.hall()+" · "+time(time)+" · "+plan.title())
                        .append(" · ").append(tr("status."+plan.status().toLowerCase(Locale.ROOT))));
            }
        }
        add.accept(tr("wall_controls"));
        return List.copyOf(lines);
    }
}
