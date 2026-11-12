package local.cinemamanager;

import local.cinemamanager.panels.*;
import local.cinemamanager.client.GuestPanelText;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Source-level runner for persistence, independent entries and the shared world/guest placement model. */
public final class PanelLayoutStyleChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){try{action.run();}catch(RuntimeException expected){checks++;return;}throw new AssertionError(message);}
    private static boolean near(float a,float b){return Math.abs(a-b)<.01f;}
    public static void main(String[] args){run();}
    public static void run(){
        var base=new PanelSettings(PanelSettings.Layout.POSTER_LEFT,"Hall","Saved title","20:00","private next","private footer","resource:minecraft:textures/block/stone.png",0xffffff,0,0x77eeff,100,PanelSettings.PUBLIC,"1:53","21:53",PanelSettings.TITLE|PanelSettings.TIME,"IMAX / 3D",PanelStyle.DEFAULT);
        var placement=new PanelStyle.Placement(PanelStyle.Horizontal.RIGHT,PanelStyle.Vertical.BOTTOM,-125,-250,false);
        PanelStyle style=PanelStyle.DEFAULT.with(PanelStyle.Entry.TITLE,placement).with(PanelStyle.Entry.POSTER,new PanelStyle.Placement(PanelStyle.Horizontal.LEFT,PanelStyle.Vertical.TOP,125,100,false));
        style=new PanelStyle(style.entries(),60);
        var settings=base.withStyle(style);
        check(PanelSettings.read(settings.save()).equals(settings),"format and independent placements roundtrip through NBT");
        check(PanelNetwork.JSON.fromJson(PanelNetwork.JSON.toJson(settings),PanelSettings.class).equals(settings),"format and independent placements roundtrip through JSON");
        check(settings.withPoster("").style().equals(style)&&settings.withPoster("").hiddenHeaders()==settings.hiddenHeaders(),"poster replacement preserves layout and headers");
        check(settings.withHiddenHeaders(0).style().equals(style)&&settings.withHiddenHeaders(0).screeningFormat().equals("IMAX / 3D"),"header changes retain layout and format values");
        for(var scope:PanelScope.values()){
            var projected=settings.publicOnly(scope,settings.poster());
            check(projected.style().equals(style)&&projected.hiddenHeaders()==settings.hiddenHeaders(),"public projection preserves style and headers: "+scope);
            check(projected.next().isEmpty()&&projected.footer().isEmpty(),"private administrator values stay private");
            check(projected.screeningFormat().equals(scope==PanelScope.LOBBY?"":"IMAX / 3D"),"format follows manual-scope boundary");
        }
        for(int version=0;version<=5;version++){
            var legacy=base.save();legacy.putInt("version",version);legacy.remove("style");legacy.remove("screening_format");
            var restored=PanelSettings.read(legacy);
            check(restored.style().equals(PanelStyle.DEFAULT)&&restored.screeningFormat().isEmpty(),"old NBT migrates to automatic flow: "+version);
            check(restored.hiddenHeaders()==base.hiddenHeaders()&&restored.poster().equals(base.poster()),"old NBT preserves headers and poster: "+version);
        }
        var legacyJson=com.google.gson.JsonParser.parseString(PanelNetwork.JSON.toJson(base)).getAsJsonObject();legacyJson.remove("style");legacyJson.remove("screeningFormat");
        check(PanelNetwork.JSON.fromJson(legacyJson,PanelSettings.class).style().equals(PanelStyle.DEFAULT),"missing JSON style defaults safely");
        var future=settings.save();future.getCompound("style").putInt("version",99);
        rejects(()->PanelSettings.read(future),"unsupported style version rejects strict read");
        var recovered=PanelSettings.savedOrDefault(future);
        check(recovered.style().equals(PanelStyle.DEFAULT)&&recovered.poster().equals(settings.poster())&&recovered.hiddenHeaders()==settings.hiddenHeaders()&&recovered.screeningFormat().equals(settings.screeningFormat()),"saved future style cannot erase base values or poster");
        var malformed=settings.save();malformed.putString("style","bad");rejects(()->PanelSettings.read(malformed),"wrong NBT style type rejected");
        var incomplete=settings.save();incomplete.getCompound("style").getCompound("entries").getCompound("TITLE").remove("manual_x");rejects(()->PanelSettings.read(incomplete),"incomplete entry rejected");
        rejects(()->new PanelStyle.Placement(PanelStyle.Horizontal.LEFT,PanelStyle.Vertical.TOP,513,0,false),"manual X bounded");
        rejects(()->new PanelStyle(Map.of(),19),"poster allocation bounded");
        var formatField=new GuestFields.Field("format_label","IMAX / 3D",false);
        check(GuestPanelText.fieldText(settings.withHiddenHeaders(PanelSettings.FORMAT),formatField).getString().equals("IMAX / 3D"),"format header hiding leaves its value intact");
        var fields=GuestFields.forRow(settings,PanelScope.MANUAL,PublicPanelSnapshot.EMPTY,null,true,key->key);
        check(fields.stream().anyMatch(field->field.label().equals("format_label")&&field.value().equals("IMAX / 3D")),"format is display-only guest copy");

        var area=new PanelLayout.Rect(10,20,200,100);
        var lines=List.of(new PanelLayout.LineMeasure(PanelStyle.Entry.TITLE,80),new PanelLayout.LineMeasure(PanelStyle.Entry.TIME,50),new PanelLayout.LineMeasure(PanelStyle.Entry.DURATION,30));
        var defaults=PanelLayout.placeLines(area,lines,11,9,PanelStyle.DEFAULT);
        for(int i=0;i<lines.size();i++)check(near(defaults.get(i).x(),10)&&near(defaults.get(i).y(),20+i*11)&&near(defaults.get(i).scale(),1),"default sequential flow matches old rendering");
        var custom=PanelLayout.placeLines(area,lines,11,9,style);
        check(custom.get(1).equals(defaults.get(1))&&custom.get(2).equals(defaults.get(2)),"moving title does not move time or runtime");
        check(!custom.get(0).equals(defaults.get(0)),"explicit title placement takes effect");
        var centered=PanelStyle.DEFAULT.with(PanelStyle.Entry.TEXT,new PanelStyle.Placement(PanelStyle.Horizontal.CENTER,PanelStyle.Vertical.MIDDLE,0,0,false));
        var centerPositions=PanelLayout.placeLines(area,lines,11,9,centered);
        check(near(centerPositions.get(0).x()+40,110)&&near(centerPositions.get(1).x()+25,110),"text group centers lines by measured width");
        var normalized=new PanelStyle.Placement(PanelStyle.Horizontal.LEFT,PanelStyle.Vertical.TOP,100,100,false);
        var small=PanelLayout.place(new PanelLayout.Rect(0,0,200,100),20,10,normalized);
        var large=PanelLayout.place(new PanelLayout.Rect(0,0,400,200),40,20,normalized);
        check(near(large.x(),small.x()*2)&&near(large.y(),small.y()*2),"offset units remain proportional under resize");
        var repeated=List.of(lines.get(0),lines.get(1),new PanelLayout.LineMeasure(PanelStyle.Entry.TEXT,0),lines.get(0),lines.get(1));
        var repeatedPositions=PanelLayout.placeLines(area,repeated,11,9,style);
        check(repeatedPositions.get(0).y()<repeatedPositions.get(3).y(),"same independently placed field in different programme rows does not overlap");
        var bottomTitle=PanelStyle.DEFAULT.with(PanelStyle.Entry.TITLE,new PanelStyle.Placement(PanelStyle.Horizontal.LEFT,PanelStyle.Vertical.BOTTOM,0,0,false));
        var rowBounds=new PanelLayout.Rect(0,0,200,132);
        var rowPositions=PanelLayout.placeLines(rowBounds,repeated,11,9,bottomTitle);
        float firstRowEnd=132f*2/5,secondRowStart=132f*3/5;
        for(int i:new int[]{0,1})check(rowPositions.get(i).y()+9*rowPositions.get(i).scale()<=firstRowEnd+.01f,"explicit and automatic first programme fields share its region");
        for(int i:new int[]{3,4})check(rowPositions.get(i).y()>=secondRowStart-.01f,"automatic second programme fields move into its own region");
        check(rowPositions.get(0).y()+9*rowPositions.get(0).scale()<=rowPositions.get(4).y(),"bottom-aligned first title cannot overlap second programme time");
        float oneBlock=0.92f/(.0064f*1.6f);
        var tiny=PanelLayout.areas(oneBlock,oneBlock,PanelSettings.Layout.POSTER_TOP,85).text();
        var tinyPage=PanelLayout.textPage(tiny,3,11,9);
        check(tiny.height()<9&&!tinyPage.hasIndicator()&&tinyPage.content().equals(tiny),"85 percent poster at 160 percent text omits marker when there is no room");
        for(float height:new float[]{.1f,1,7.47656f,9,11,19.99f,20,21,22,33,132})for(int count:new int[]{0,1,2,3,100}){
            var bounds=new PanelLayout.Rect(10,20,200,height);
            var page=PanelLayout.textPage(bounds,count,11,9);
            check(page.content().height()>0&&page.content().y()>=bounds.y()&&page.content().y()+page.content().height()<=bounds.y()+height+.01f,"page content is positive and fully inside text bounds");
            if(page.hasIndicator()){
                var marker=page.indicator();
                check(page.content().y()+page.content().height()<=marker.y()&&marker.y()+marker.height()<=bounds.y()+height+.01f,"reserved marker stays bounded and separate from content");
                check(page.content().height()>=9&&marker.height()>=9,"marker only shown alongside one readable content line");
            }
            var fitted=PanelLayout.placeLines(page.content(),List.of(lines.get(0)),11,9,PanelStyle.DEFAULT).getFirst();
            check(fitted.y()+9*fitted.scale()<=page.content().y()+page.content().height()+.01f,"tiny page fits actual glyph height inside content");
        }
        var unknown=List.of(new PanelLayout.LineMeasure(PanelStyle.Entry.TEXT,70),new PanelLayout.LineMeasure(PanelStyle.Entry.TEXT,40));
        var unknownPositions=PanelLayout.placeLines(area,unknown,11,9,centered);
        check(near(unknownPositions.get(1).y()-unknownPositions.get(0).y(),11),"unknown lines retain grouped automatic flow");
        for(float width:new float[]{1,30,200,2000})for(float height:new float[]{1,25,100,700})for(var layout:PanelSettings.Layout.values())for(int percent:new int[]{0,20,85}){
            var areas=PanelLayout.areas(width,height,layout,percent);
            for(var rect:List.of(areas.text(),areas.poster()))check(rect.x()>=0&&rect.y()>=0&&rect.x()+rect.width()<=width+.01f&&rect.y()+rect.height()<=height+.01f,"all panel allocations stay inside panel");
            for(var horizontal:PanelStyle.Horizontal.values())for(var vertical:PanelStyle.Vertical.values())for(int offset:new int[]{-512,0,512}){
                var extreme=PanelStyle.DEFAULT.with(PanelStyle.Entry.TITLE,new PanelStyle.Placement(horizontal,vertical,offset,offset,false));
                var measures=List.of(new PanelLayout.LineMeasure(PanelStyle.Entry.TITLE,9000),new PanelLayout.LineMeasure(PanelStyle.Entry.TITLE,6000));
                var positions=PanelLayout.placeLines(areas.text(),measures,11,9,extreme);
                for(int i=0;i<positions.size();i++){var pos=positions.get(i);var rect=areas.text();check(pos.x()>=rect.x()-.01f&&pos.y()>=rect.y()-.01f&&pos.x()+measures.get(i).width()*pos.scale()<=rect.x()+rect.width()+.01f&&pos.y()+9*pos.scale()<=rect.y()+rect.height()+.01f,"long text and extreme offsets stay bounded");}
            }
        }
        System.out.println("PASS: "+checks+" independent panel layout, format and persistence checks.");
    }
}
