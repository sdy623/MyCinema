package local.cinemamanager.seats;

import com.google.gson.*;
import java.util.List;

/** Explicit endpoint selection carried by one owner-scoped seat card. */
public record RowSelectionState(String actor,String controller,String dimension,long manager,String card,
        RowBatch.Request settings,boolean active,Long firstPosition,String firstUid,String facing) {
    public RowSelectionState {
        actor=SeatRegistry.uuid(actor);controller=SeatRegistry.uuid(controller);card=SeatRegistry.uuid(card);
        if(dimension==null||dimension.length()>128||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||settings==null)
            throw SeatRegistry.error("batch_invalid");
        firstUid=firstUid==null?"":firstUid;facing=facing==null?"":facing;
        if(firstPosition==null){if(!firstUid.isEmpty()||!facing.isEmpty())throw SeatRegistry.error("batch_invalid");}
        else {firstUid=SeatRegistry.uuid(firstUid);if(!active||!List.of("north","south","east","west").contains(facing))throw SeatRegistry.error("batch_invalid");}
    }
    public void requireScope(String actor,String controller,String dimension,long manager,String card){
        if(!this.actor.equals(actor)||!this.controller.equals(controller)||!this.dimension.equals(dimension)
                ||this.manager!=manager||!this.card.equals(card))throw SeatRegistry.error("batch_scope");
    }
    public RowSelectionState first(long position,String uid,String facing){
        if(!active)throw SeatRegistry.error("batch_inactive");
        return new RowSelectionState(actor,controller,dimension,manager,card,settings,true,position,uid,facing);
    }
    public RowSelectionState clearFirst(){return new RowSelectionState(actor,controller,dimension,manager,card,settings,active,null,"","");}
    public RowSelectionState complete(){return new RowSelectionState(actor,controller,dimension,manager,card,settings,false,null,"","");}
    public RowSelectionState nextRow(){
        if(settings.row()>=SeatRegistry.MAX_NUMBER)throw SeatRegistry.error("batch_row_limit");
        RowBatch.Request next=new RowBatch.Request(settings.hall(),settings.zone(),settings.row()+1,settings.start(),settings.direction(),settings.end(),settings.rowScheme());
        return new RowSelectionState(actor,controller,dimension,manager,card,next,true,null,"","");
    }
    public String encode(){return SeatRegistry.JSON.toJson(this);}
    public static RowSelectionState decode(String raw){
        try{
            if(raw==null||raw.length()>4096)throw SeatRegistry.error("batch_invalid");
            JsonObject o=JsonParser.parseString(raw).getAsJsonObject();
            for(String key:List.of("actor","controller","dimension","card"))
                if(!o.has(key)||!o.get(key).isJsonPrimitive()||!o.getAsJsonPrimitive(key).isString())throw SeatRegistry.error("batch_invalid");
            if(!o.has("active")||!o.get("active").isJsonPrimitive()||!o.getAsJsonPrimitive("active").isBoolean())throw SeatRegistry.error("batch_invalid");
            exact(o,"manager",true);if(o.has("firstPosition")&&!o.get("firstPosition").isJsonNull())exact(o,"firstPosition",true);
            JsonObject request=o.getAsJsonObject("settings");
            for(String key:List.of("hall","zone","rowScheme"))if(!request.has(key)||!request.get(key).isJsonPrimitive()||!request.getAsJsonPrimitive(key).isString())throw SeatRegistry.error("batch_invalid");
            for(String key:List.of("row","start","direction","end"))exact(request,key,false);
            return SeatRegistry.JSON.fromJson(o,RowSelectionState.class);
        }catch(RuntimeException failure){throw SeatRegistry.error("batch_invalid");}
    }
    private static void exact(JsonObject object,String key,boolean wide){
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw SeatRegistry.error("batch_invalid");
        if(wide)value.getAsBigDecimal().longValueExact();else value.getAsBigDecimal().intValueExact();
    }
}
