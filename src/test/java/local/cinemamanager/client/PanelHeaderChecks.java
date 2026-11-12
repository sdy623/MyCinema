package local.cinemamanager.client;

import com.google.gson.JsonParser;
import local.cinemamanager.panels.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.List;

/** Exercises the actual shared display component without a Minecraft window or a font atlas. */
public final class PanelHeaderChecks {
    private static int checks;
    private PanelHeaderChecks(){}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){try{action.run();}catch(RuntimeException expected){checks++;return;}throw new AssertionError(message);}

    public static void main(String[] args){run();}
    public static void run(){
        PanelSettings manual=new PanelSettings(PanelSettings.Layout.POSTER_LEFT,"Lobby A","Film: 夜の映写","23:30",
                "PRIVATE_NEXT","PRIVATE_FOOTER","",0xffffff,0,0x44ffff,100,PanelSettings.PUBLIC,"1:53:17","01:23");
        check(manual.hiddenHeaders()==0&&PanelSettings.DEFAULT.hiddenHeaders()==0,"legacy constructors and defaults show every header");
        for(int version=0;version<=4;version++){
            CompoundTag legacy=manual.save();legacy.putInt("version",version);legacy.remove("hidden_headers");
            PanelSettings restored=PanelSettings.read(legacy);
            check(restored.hiddenHeaders()==0,"old NBT defaults to visible headers: "+version);
            check(restored.title().equals(manual.title())&&restored.manualTime().equals(manual.manualTime())&&restored.duration().equals(manual.duration()),"old NBT retains values: "+version);
        }
        var legacyJson=JsonParser.parseString(PanelNetwork.JSON.toJson(manual)).getAsJsonObject();legacyJson.remove("hiddenHeaders");
        check(PanelNetwork.JSON.fromJson(legacyJson,PanelSettings.class).equals(manual),"missing legacy JSON flags retain all headers and values");

        long start=java.time.Instant.parse("2030-01-01T14:30:00Z").toEpochMilli();
        var row=new PublicShowRow("show","Automatic: 映画","Lobby A",start,6_797_000L,start+6_797_000L);
        var snapshot=new PublicPanelSnapshot("Asia/Tokyo",start-600_000L,List.of(row));
        int[] flags={PanelSettings.TITLE,PanelSettings.TIME,PanelSettings.DURATION};
        for(int combination=0;combination<8;combination++){
            int hidden=0;for(int bit=0;bit<flags.length;bit++)if((combination&(1<<bit))!=0)hidden|=flags[bit];
            PanelSettings settings=manual.withHiddenHeaders(hidden);
            check(PanelSettings.read(settings.save()).equals(settings),"all header choices survive NBT: "+combination);
            check(settings.save().getInt("version")==6,"header settings use schema v6");
            check(PanelNetwork.JSON.fromJson(PanelNetwork.JSON.toJson(settings),PanelSettings.class).equals(settings),"all header choices survive JSON: "+combination);
            check(settings.withPoster("resource:minecraft:textures/block/stone.png").hiddenHeaders()==hidden,"poster edit retains header choices");
            for(PanelScope scope:PanelScope.values()){
                check(settings.publicOnly(scope).hiddenHeaders()==hidden&&settings.publicOnly(scope,"").hiddenHeaders()==hidden,"both public projections retain header choices: "+scope);
                for(boolean overrides:List.of(false,true)){
                    var expected=GuestFields.forRow(manual,scope,snapshot,row,overrides,key->key);
                    var fields=GuestFields.forRow(settings,scope,snapshot,row,overrides,key->key);
                    check(fields.equals(expected),"headers never suppress or rewrite field values: "+scope+"/"+overrides);
                    for(var field:fields)verifyText(settings,field);
                }
            }
            for(PanelScope scope:List.of(PanelScope.MANUAL,PanelScope.HALL)){
                var fields=GuestFields.forRow(settings,scope,PublicPanelSnapshot.EMPTY,null,true,key->key);
                check(fields.size()==3,"unplanned manual values remain visible with every header choice");
                check(fields.get(0).value().equals(manual.title())&&fields.get(1).value().equals("23:30–day.next_day 01:23")&&fields.get(2).value().equals(manual.duration()),"manual title/time/runtime and embedded colons remain intact");
                check(fields.get(2).label().equals("duration_manual_label"),"manual runtime uses its existing separate header");
                for(var field:fields)verifyText(settings,field);
            }
        }
        PanelSettings allHidden=manual.withHiddenHeaders(PanelSettings.HEADER_FIELDS);
        verifyText(allHidden,new GuestFields.Field("duration_label","—",false));
        verifyText(allHidden,new GuestFields.Field("duration_manual_label","—",false));
        verifyText(allHidden,new GuestFields.Field("hall_label","Lobby A",true));
        var onlyFilm=new PanelSettings(manual.layout(),"",manual.title(),manual.time(),"","","",0xffffff,0,0,100,PanelSettings.TITLE,manual.duration(),manual.endTime(),PanelSettings.HEADER_FIELDS);
        var onlyFilmFields=GuestFields.forRow(onlyFilm,PanelScope.MANUAL,PublicPanelSnapshot.EMPTY,null,true,key->key);
        check(onlyFilmFields.size()==1&&literal(GuestPanelText.fieldText(onlyFilm,onlyFilmFields.getFirst())).equals(manual.title()),"whole-field visibility remains independent of header visibility");
        check(GuestFields.forRow(PanelSettings.DEFAULT.withHiddenHeaders(PanelSettings.HEADER_FIELDS),PanelScope.MANUAL,PublicPanelSnapshot.EMPTY,null,true,key->key).isEmpty(),"header flags do not invent empty programme rows");
        for(int invalid:new int[]{-1,PanelSettings.HALL,PanelSettings.STATUS,PanelSettings.NEXT,PanelSettings.FOOTER,256}){
            rejects(()->manual.withHiddenHeaders(invalid),"unsupported header bits rejected: "+invalid);
            CompoundTag invalidTag=manual.save();invalidTag.putInt("hidden_headers",invalid);
            rejects(()->PanelSettings.read(invalidTag),"invalid persisted header bits rejected: "+invalid);
        }
        CompoundTag wrongType=manual.save();wrongType.putString("hidden_headers","0");
        rejects(()->PanelSettings.read(wrongType),"malformed header NBT is rejected instead of silently reinterpreted");
        System.out.println("PASS: "+checks+" header checks; per-field labels, retained values, manual/automatic copy, public projections and NBT/JSON compatibility.");
    }
    private static void verifyText(PanelSettings settings,GuestFields.Field field){
        int flag=switch(field.label()){
            case "title_label"->PanelSettings.TITLE;
            case "time_label"->PanelSettings.TIME;
            case "duration_label","duration_manual_label"->PanelSettings.DURATION;
            default->0;
        };
        Component actual=GuestPanelText.fieldText(settings,field);
        if((settings.hiddenHeaders()&flag)!=0){
            check(literal(actual).equals(field.value())&&actual.getSiblings().isEmpty(),"shared rendered component hides only the selected header: "+field.label());
            return;
        }
        check(actual.getSiblings().size()==3,"header component has label, separator and value: "+field.label());
        check(actual.getSiblings().get(0).getContents() instanceof TranslatableContents label
                        && label.getKey().equals("mycinema.panel."+field.label()),
                "shared rendered component uses the selected translation key: "+field.label());
        check(literal(actual.getSiblings().get(1)).equals(": ")&&literal(actual.getSiblings().get(2)).equals(field.value()),
                "shared rendered component retains separator and value: "+field.label());
    }
    private static String literal(Component component){
        return component.getContents() instanceof PlainTextContents.LiteralContents text?text.text():"";
    }
}
