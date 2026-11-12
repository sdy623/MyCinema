package local.cinemamanager.seats;

import com.google.gson.*;
import java.util.List;

/** One manually chosen row; no layout, route or neighboring block is inferred. */
public record ContinuousSeatState(String actor,String controller,String dimension,long manager,
        String hall,String zone,int row,int start,int next,int direction,int end,boolean active,long revision) {
    public ContinuousSeatState {
        actor=SeatRegistry.uuid(actor);controller=SeatRegistry.uuid(controller);hall=SeatRegistry.uuid(hall);
        zone=SeatRegistry.label(zone,32,false);
        if(dimension==null||dimension.length()>128||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                ||row<1||row>SeatRegistry.MAX_NUMBER||start<1||start>SeatRegistry.MAX_NUMBER
                ||next<1||next>SeatRegistry.MAX_NUMBER||(direction!=1&&direction!=-1)
                ||end<0||end>SeatRegistry.MAX_NUMBER||revision<0||revision==Long.MAX_VALUE
                ||(next-start)*direction<0||end!=0&&((end-start)*direction<0||(end-next)*direction<0)
                ||!active&&next!=(end==0?(direction==1?SeatRegistry.MAX_NUMBER:1):end))
            throw SeatRegistry.error("continuous_invalid");
    }
    public static ContinuousSeatState start(String actor,String controller,String dimension,long manager,
            String hall,String zone,int row,int start,int direction,int end,long revision){
        return new ContinuousSeatState(actor,controller,dimension,manager,hall,zone,row,start,start,direction,end,true,revision);
    }
    public void requireScope(String actor,String controller,String dimension,long manager){
        if(!this.actor.equals(actor)||!this.controller.equals(controller)||!this.dimension.equals(dimension)||this.manager!=manager)
            throw SeatRegistry.error("continuous_scope");
    }
    public record Bound(ContinuousSeatState state,SeatRegistry.Seat seat,boolean created){}
    public Bound bind(SeatRegistry registry,long position,String block){
        return bind(registry,position,block,"",GuidePreset.RowScheme.NUMERIC);
    }
    public Bound bind(SeatRegistry registry,long position,String block,String seatUid,GuidePreset.RowScheme rowScheme){
        if(!active)throw SeatRegistry.error("continuous_finished");
        registry.requireRevision(revision);
        var existing=registry.at(position);
        if(existing.isPresent()){
            if(existing.get().verifiedIdentity()&&!existing.get().seatUid().equals(seatUid))throw SeatRegistry.error("row_replaced_uid");
            return new Bound(this,existing.get(),false);
        }
        // Validate the next state before changing the registry, including the revision ceiling.
        boolean finished=next==(end==0?(direction==1?SeatRegistry.MAX_NUMBER:1):end);
        ContinuousSeatState advanced=new ContinuousSeatState(actor,controller,dimension,manager,hall,zone,row,start,
                finished?next:next+direction,direction,end,!finished,revision+1);
        SeatRegistry.Seat saved=registry.put(revision,"",hall,zone,row,next,position,SeatRegistry.Status.AVAILABLE,block,seatUid,rowScheme);
        return new Bound(advanced,saved,true);
    }
    public ContinuousSeatState nextRow(long revision){
        if(row==SeatRegistry.MAX_NUMBER)throw SeatRegistry.error("continuous_row_limit");
        return start(actor,controller,dimension,manager,hall,zone,row+1,start,direction,end,revision);
    }
    public String encode(){return SeatRegistry.JSON.toJson(this);}
    public static ContinuousSeatState decode(String raw){
        try{
            if(raw==null||raw.length()>4096)throw SeatRegistry.error("continuous_invalid");
            JsonObject object=JsonParser.parseString(raw).getAsJsonObject();
            for(String key:List.of("row","start","next","direction","end","revision","manager")){
                JsonElement value=object.get(key);
                if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw SeatRegistry.error("continuous_invalid");
                if(key.equals("revision")||key.equals("manager"))value.getAsBigDecimal().longValueExact();else value.getAsBigDecimal().intValueExact();
            }
            if(!object.has("active")||!object.get("active").isJsonPrimitive()||!object.getAsJsonPrimitive("active").isBoolean())throw SeatRegistry.error("continuous_invalid");
            for(String key:List.of("actor","controller","dimension","hall","zone"))
                if(!object.has(key)||!object.get(key).isJsonPrimitive()||!object.getAsJsonPrimitive(key).isString())throw SeatRegistry.error("continuous_invalid");
            return SeatRegistry.JSON.fromJson(object,ContinuousSeatState.class);
        }catch(RuntimeException invalid){throw SeatRegistry.error("continuous_invalid");}
    }
}
