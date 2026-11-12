package local.cinemamanager.seats;

import com.google.gson.*;
import java.util.*;

/** Scope, strict persisted-state parsing and display identity checks without Minecraft bootstrap. */
public final class RowSelectionStateChecks {
    private static final String ACTOR=new UUID(0,1).toString(),CONTROLLER=new UUID(0,2).toString(),CARD=new UUID(0,3).toString(),HALL=new UUID(0,4).toString(),UID=new UUID(0,5).toString();
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void reject(String code,Runnable action){
        try{action.run();}catch(IllegalArgumentException expected){check(expected.getMessage().equals("mycinema.seats."+code),"expected "+code+", got "+expected.getMessage());return;}
        throw new AssertionError("Accepted invalid operation: "+code);
    }
    private static RowSelectionState start(int row){return new RowSelectionState(ACTOR,CONTROLLER,"minecraft:overworld",42,CARD,new RowBatch.Request(HALL,"Lower",row,12,-1,1,GuidePreset.RowScheme.LETTERS),true,null,"","");}
    private static void invalidField(String base,String key,JsonElement value){JsonObject json=JsonParser.parseString(base).getAsJsonObject();json.add(key,value);reject("batch_invalid",()->RowSelectionState.decode(json.toString()));}
    private static void invalidSetting(String base,String key,JsonElement value){JsonObject json=JsonParser.parseString(base).getAsJsonObject();json.getAsJsonObject("settings").add(key,value);reject("batch_invalid",()->RowSelectionState.decode(json.toString()));}
    public static void run(){
        RowSelectionState initial=start(26),first=initial.first(777,UID,"north");
        check(initial.active()&&initial.firstPosition()==null&&initial.settings().rowLabel().equals("Z"),"new configuration waits for an explicit endpoint");
        check(first.firstPosition()==777&&first.firstUid().equals(UID)&&first.facing().equals("north")&&initial.firstPosition()==null,"first click creates immutable scoped state");
        check(RowSelectionState.decode(initial.encode()).equals(initial)&&RowSelectionState.decode(first.encode()).equals(first),"configured and endpoint states survive item JSON round trips");
        check(first.clearFirst().equals(initial),"restart clears only explicit endpoint information");
        RowSelectionState complete=first.complete();
        check(!complete.active()&&complete.firstPosition()==null&&complete.firstUid().isEmpty()&&complete.facing().isEmpty()&&complete.settings().equals(first.settings()),"completed row retains settings without a live endpoint");
        check(RowSelectionState.decode(complete.encode()).equals(complete),"completed state survives reload");
        reject("batch_inactive",()->complete.first(778,UID,"north"));
        RowSelectionState next=complete.nextRow();
        check(next.active()&&next.firstPosition()==null&&next.settings().row()==27&&next.settings().rowLabel().equals("AA"),"Z advances to plain AA using unchanged numeric identity");
        check(next.settings().start()==12&&next.settings().direction()==-1&&next.settings().end()==1&&next.settings().zone().equals("Lower")&&next.settings().rowScheme()==GuidePreset.RowScheme.LETTERS,"next row preserves original numbering configuration");
        next.requireScope(ACTOR,CONTROLLER,"minecraft:overworld",42,CARD);
        reject("batch_scope",()->next.requireScope(UID,CONTROLLER,"minecraft:overworld",42,CARD));
        reject("batch_scope",()->next.requireScope(ACTOR,UID,"minecraft:overworld",42,CARD));
        reject("batch_scope",()->next.requireScope(ACTOR,CONTROLLER,"minecraft:the_nether",42,CARD));
        reject("batch_scope",()->next.requireScope(ACTOR,CONTROLLER,"minecraft:overworld",43,CARD));
        reject("batch_scope",()->next.requireScope(ACTOR,CONTROLLER,"minecraft:overworld",42,UID));
        reject("batch_row_limit",()->start(999).nextRow());
        reject("batch_invalid",()->new RowSelectionState(ACTOR,CONTROLLER,"bad dimension",42,CARD,initial.settings(),true,null,"",""));
        reject("batch_invalid",()->new RowSelectionState(ACTOR,CONTROLLER,"minecraft:overworld",42,CARD,initial.settings(),false,777L,UID,"north"));
        reject("batch_invalid",()->new RowSelectionState(ACTOR,CONTROLLER,"minecraft:overworld",42,CARD,initial.settings(),true,null,UID,"north"));
        reject("batch_invalid",()->initial.first(777,UID,"up"));
        for(String malformed:List.of("{}","null","[]","{broken}"," ".repeat(4097)))reject("batch_invalid",()->RowSelectionState.decode(malformed));
        String raw=first.encode();
        for(String key:List.of("actor","controller","dimension","card","firstUid","facing"))invalidField(raw,key,new JsonPrimitive(4));
        invalidField(raw,"active",new JsonPrimitive("true"));invalidField(raw,"active",JsonNull.INSTANCE);
        invalidField(raw,"manager",new JsonPrimitive("42"));invalidField(raw,"manager",new JsonPrimitive(42.5));
        invalidField(raw,"firstPosition",new JsonPrimitive("777"));invalidField(raw,"firstPosition",new JsonPrimitive(777.5));
        invalidField(raw,"firstPosition",new JsonPrimitive(new java.math.BigDecimal("9223372036854775808")));
        invalidField(raw,"settings",JsonNull.INSTANCE);
        for(String key:List.of("row","start","direction","end")){
            invalidSetting(raw,key,new JsonPrimitive("1"));invalidSetting(raw,key,new JsonPrimitive(1.5));invalidSetting(raw,key,JsonNull.INSTANCE);
        }
        invalidSetting(raw,"zone",new JsonPrimitive(123));invalidSetting(raw,"hall",new JsonPrimitive(123));
        invalidSetting(raw,"rowScheme",new JsonPrimitive("ROMAN"));invalidSetting(raw,"row",new JsonPrimitive(1000));
        for(GuidePreset.RowScheme scheme:GuidePreset.RowScheme.values())for(int row=1;row<=SeatRegistry.MAX_NUMBER;row++){
            String label=SeatRegistry.rowLabel(row,scheme);
            check(RowBatch.parseRowLabel(label,scheme)==row,"every display label maps to the same numeric identity");
            if(scheme==GuidePreset.RowScheme.LETTERS)check(RowBatch.parseRowLabel(" "+label.toLowerCase(Locale.ROOT)+" ",scheme)==row,"ASCII case and harmless outer whitespace preserve identity");
        }
        for(String invalid:List.of("","0","1000","-1","+1","1.0","１","A"))reject("row_range",()->RowBatch.parseRowLabel(invalid,GuidePreset.RowScheme.NUMERIC));
        for(String invalid:List.of("","0","A1","A B","AAAA","ALL","ROW A","ß","ı","ſ","Ａ"))reject("row_range",()->RowBatch.parseRowLabel(invalid,GuidePreset.RowScheme.LETTERS));
        reject("row_range",()->RowBatch.parseRowLabel(null,GuidePreset.RowScheme.NUMERIC));reject("row_range",()->RowBatch.parseRowLabel("1",null));
        System.out.println("RowSelectionStateChecks passed: "+checks+" checks");
    }
    public static void main(String[] args){run();}
}
