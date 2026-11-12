package local.cinemamanager.seats;

import com.google.gson.*;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.CinemaManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.util.*;

/** Server-held, short-lived preview. Only explicit card clicks establish endpoints. */
@EventBusSubscriber(modid=CinemaManager.ID)
public final class RowBatchController {
    public static final int MAX_SPAN=128;
    private static final long PREVIEW_TICKS=6000;
    private static final Map<UUID,Pending> PENDING=new HashMap<>();
    private record Pending(ServerLevel level,String token,String card,UUID controller,BlockPos manager,
                           RowSelectionState selection,long endPosition,String endUid,RowBatch.Preview preview,long created){}
    public record Data(String kind,List<SeatNetwork.Hall> halls,long revision,boolean editable,String card,
                       RowSelectionState selection,String token,RowBatch.Preview preview,String notice,int applied){}
    public static final class Args {
        public String card="",hall="",zone="",rowScheme="NUMERIC",token="";
        public Long revision;
        public int row=1,number=1,direction=1,end;
    }
    public static void clear(ServerPlayer actor){PENDING.remove(actor.getUUID());}
    @SubscribeEvent public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer actor)clear(actor);}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){PENDING.clear();}
    private static String notice(RuntimeException failure){String key=failure.getMessage();return key!=null&&key.startsWith("mycinema.seats.")?key:"mycinema.seats.batch_invalid";}
    private static Args parse(String raw){
        try{
            if(raw==null||raw.length()>8192)throw SeatRegistry.error("batch_invalid");
            JsonObject object=JsonParser.parseString(raw).getAsJsonObject();
            for(String key:List.of("card","hall","zone","rowScheme","token"))if(object.has(key)&&(!object.get(key).isJsonPrimitive()||!object.getAsJsonPrimitive(key).isString()))throw SeatRegistry.error("batch_invalid");
            for(String key:List.of("row","number","direction","end"))if(object.has(key)){
                JsonElement value=object.get(key);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw SeatRegistry.error("batch_invalid");value.getAsBigDecimal().intValueExact();
            }
            if(object.has("revision")){
                JsonElement value=object.get("revision");if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw SeatRegistry.error("batch_invalid");value.getAsBigDecimal().longValueExact();
            }
            Args args=SeatRegistry.JSON.fromJson(object,Args.class);SeatRegistry.uuid(args.card);
            if(args.token==null||!args.token.isEmpty()&&!SeatRegistry.uuid(args.token).equals(args.token))throw SeatRegistry.error("batch_invalid");
            return args;
        }catch(RuntimeException failure){throw SeatRegistry.error("batch_invalid");}
    }
    private static SeatStorage storage(ServerPlayer actor,ManagerTile manager){
        if(!SeatNetwork.allowed(actor,manager)||manager.getLevel()!=actor.level())throw SeatRegistry.error("permission");
        SeatStorage store=SeatStorage.get((ServerLevel)actor.level(),manager.identity);store.requireEditable();return store;
    }
    private static void hall(ManagerTile manager,String id){if(manager.state.halls.stream().noneMatch(h->h.id.equals(id)))throw SeatRegistry.error("hall_missing");}
    private static RowSelectionState selection(ServerPlayer actor,ManagerTile manager,ItemStack card){
        RowSelectionState state=SeatBindingItem.rowSelection(card,actor,manager);if(state==null)throw SeatRegistry.error("batch_inactive");hall(manager,state.settings().hall());return state;
    }
    private static CinemaSeatTile endpoint(ServerPlayer actor,BlockPos pos){
        if(!actor.level().hasChunkAt(pos)||actor.distanceToSqr(Vec3.atCenterOf(pos))>64||!actor.level().mayInteract(actor,pos))throw SeatRegistry.error("click_seat");
        var hit=actor.level().clip(new ClipContext(actor.getEyePosition(),Vec3.atCenterOf(pos),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,actor));
        if(hit.getType()!=HitResult.Type.BLOCK||!hit.getBlockPos().equals(pos))throw SeatRegistry.error("click_seat");
        return CinemaSeatBlock.findSeat(actor.level(),pos).orElseThrow(()->SeatRegistry.error("batch_real_chair"));
    }
    private static void checkEndpoint(ServerLevel level,long position,String uid){
        if(!level.hasChunkAt(BlockPos.of(position))||!CinemaSeatBlock.isValidSeat(level,BlockPos.of(position),UUID.fromString(uid)))throw SeatRegistry.error("row_replaced_uid");
    }
    /** Enumerates only the exact, loaded, straight span selected by the owner. */
    public static List<RowBatch.Candidate> candidates(ServerPlayer actor,ManagerTile manager,RowSelectionState selection,long end,String endUid){
        if(selection.firstPosition()==null)throw SeatRegistry.error("batch_first");
        ServerLevel level=(ServerLevel)actor.level();BlockPos first=BlockPos.of(selection.firstPosition()),last=BlockPos.of(end);
        long dx=(long)last.getX()-first.getX(),dz=(long)last.getZ()-first.getZ();
        if(first.getY()!=last.getY()||dx!=0&&dz!=0||Math.abs(dx)+Math.abs(dz)>=MAX_SPAN)throw SeatRegistry.error("batch_straight");
        if(dx!=0&&(selection.facing().equals("east")||selection.facing().equals("west"))
                ||dz!=0&&(selection.facing().equals("north")||selection.facing().equals("south")))throw SeatRegistry.error("batch_perpendicular");
        checkEndpoint(level,first.asLong(),selection.firstUid());checkEndpoint(level,end,endUid);
        if(!level.getBlockState(first).getValue(CinemaSeatBlock.FACING).getSerializedName().equals(selection.facing()))throw SeatRegistry.error("row_stale_preview");
        int sx=Long.signum(dx),sz=Long.signum(dz),length=(int)(Math.abs(dx)+Math.abs(dz))+1;
        List<RowBatch.Candidate> result=new ArrayList<>();
        for(int i=0;i<length;i++){
            BlockPos pos=first.offset(i*sx,0,i*sz);long packed=pos.asLong();
            if(!level.hasChunkAt(pos)){result.add(RowBatch.Candidate.unloaded(packed));continue;}
            if(!level.mayInteract(actor,pos)){result.add(RowBatch.Candidate.blocked(packed));continue;}
            var tile=CinemaSeatBlock.findSeat(level,pos);
            if(tile.isPresent()){
                CinemaSeatTile seat=tile.get();if(!seat.canBind(manager.identity))throw SeatRegistry.error("batch_other_controller");
                result.add(RowBatch.Candidate.chair(packed,seat.seatUid(),level.getBlockState(pos).getValue(CinemaSeatBlock.FACING).getSerializedName(),SeatBindings.blockType(level.getBlockState(pos))));
            }else if(level.getBlockState(pos).isAir())result.add(RowBatch.Candidate.gap(packed));
            else result.add(RowBatch.Candidate.wrongBlock(packed));
        }
        return List.copyOf(result);
    }
    public static void click(ServerPlayer actor,ManagerTile manager,ItemStack card,BlockPos target){
        SeatStorage store=storage(actor,manager);if(SeatBindingItem.held(actor,manager,SeatBindingItem.card(card))!=card)throw SeatRegistry.error("batch_card");
        RowSelectionState state=selection(actor,manager,card);if(!state.active())throw SeatRegistry.error("batch_inactive");
        CinemaSeatTile seat=endpoint(actor,target);if(!seat.canBind(manager.identity))throw SeatRegistry.error("batch_other_controller");
        if(state.firstPosition()==null){
            clear(actor);SeatBindingItem.rowSelection(card,state.first(target.asLong(),seat.seatUid().toString(),seat.getBlockState().getValue(CinemaSeatBlock.FACING).getSerializedName()));
            actor.containerMenu.broadcastChanges();actor.displayClientMessage(Component.translatable("mycinema.seats.batch_choose_last"),true);return;
        }
        clear(actor);List<RowBatch.Candidate> row=candidates(actor,manager,state,target.asLong(),seat.seatUid().toString());
        RowBatch.Preview preview=RowBatch.preview(store.registry(),store.registry().revision(),state.settings(),row);
        long now=actor.level().getGameTime();PENDING.entrySet().removeIf(e->e.getValue().level().getServer()!=actor.getServer()||e.getValue().level().getGameTime()<e.getValue().created()||e.getValue().level().getGameTime()-e.getValue().created()>PREVIEW_TICKS);
        if(PENDING.size()>=128)throw SeatRegistry.error("batch_busy");
        PENDING.put(actor.getUUID(),new Pending((ServerLevel)actor.level(),UUID.randomUUID().toString(),SeatBindingItem.card(card),manager.identity,manager.getBlockPos(),state,target.asLong(),seat.seatUid().toString(),preview,now));
        open(actor,manager,card,"",true,"");
    }
    private static Pending pending(ServerPlayer actor,ManagerTile manager,String card){
        Pending p=PENDING.get(actor.getUUID());if(p==null)return null;long now=actor.level().getGameTime();
        if(p.level()!=actor.level()||!p.card().equals(card)||!p.controller().equals(manager.identity)||!p.manager().equals(manager.getBlockPos())||now<p.created()||now-p.created()>PREVIEW_TICKS){clear(actor);return null;}
        return p;
    }
    public static void open(ServerPlayer actor,ManagerTile manager,ItemStack card,String request,boolean open,String notice){send(actor,manager,card,request,open,notice,0);}
    private static void send(ServerPlayer actor,ManagerTile manager,ItemStack card,String request,boolean open,String notice,int applied){
        if(!SeatNetwork.allowed(actor,manager))return;
        SeatStorage store=SeatStorage.get((ServerLevel)actor.level(),manager.identity);String id=SeatBindingItem.card(card);RowSelectionState selected=null;
        try{if(!id.isEmpty()){SeatBindingItem.held(actor,manager,id);selected=SeatBindingItem.rowSelection(card,actor,manager);}}catch(RuntimeException failure){id="";if(notice.isEmpty())notice=notice(failure);}
        Pending p=pending(actor,manager,id);
        Data data=new Data("row_batch",manager.state.halls.stream().map(h->new SeatNetwork.Hall(h.id,h.name)).toList(),store.registry().revision(),store.editable(),id,selected,p==null?"":p.token(),p==null?null:p.preview(),notice,applied);
        String payload=SeatRegistry.JSON.toJson(data);
        if(payload.length()>60_000){clear(actor);data=new Data("row_batch",data.halls(),data.revision(),data.editable(),id,selected,"",null,"mycinema.seats.batch_preview_limit",0);payload=SeatRegistry.JSON.toJson(data);}
        PacketDistributor.sendToPlayer(actor,new SeatNetwork.View(manager.getBlockPos(),manager.identity.toString(),request,open,payload));
    }
    public static void apply(ServerPlayer actor,ManagerTile manager,SeatNetwork.Request packet){
        ItemStack card=ItemStack.EMPTY;String message="";int applied=0;
        try{
            if(!SeatNetwork.allowed(actor,manager))throw SeatRegistry.error("permission");
            Args args=parse(packet.json());card=SeatBindingItem.held(actor,manager,args.card);
            // Leaving a local card mode must not depend on a damaged read-only registry.
            if(packet.action().equals("row_batch_exit")){clear(actor);SeatBindingItem.rowSelection(card,null);actor.containerMenu.broadcastChanges();send(actor,manager,card,packet.request(),false,"mycinema.seats.batch_exited",0);return;}
            SeatStorage store=storage(actor,manager);
            switch(packet.action()){
                case "row_batch_view"->{}
                case "row_batch_start"->{
                    if(args.revision==null)throw SeatRegistry.error("row_stale");store.registry().requireRevision(args.revision);hall(manager,args.hall);
                    RowBatch.Request settings=new RowBatch.Request(args.hall,args.zone,args.row,args.number,args.direction,args.end,GuidePreset.RowScheme.valueOf(args.rowScheme));
                    RowSelectionState selected=new RowSelectionState(actor.getUUID().toString(),manager.identity.toString(),actor.level().dimension().location().toString(),manager.getBlockPos().asLong(),args.card,settings,true,null,"","");
                    clear(actor);SeatBindingItem.continuous(card,null);SeatBindingItem.rowSelection(card,selected);actor.containerMenu.broadcastChanges();message="mycinema.seats.batch_choose_first";
                }
                case "row_batch_next"->{
                    RowSelectionState next=selection(actor,manager,card).nextRow();clear(actor);SeatBindingItem.continuous(card,null);SeatBindingItem.rowSelection(card,next);actor.containerMenu.broadcastChanges();message="mycinema.seats.batch_choose_first";
                }
                case "row_batch_restart"->{
                    RowSelectionState old=selection(actor,manager,card);if(!old.active())throw SeatRegistry.error("batch_inactive");clear(actor);SeatBindingItem.rowSelection(card,old.clearFirst());actor.containerMenu.broadcastChanges();message="mycinema.seats.batch_choose_first";
                }
                case "row_batch_confirm"->{
                    Pending p=pending(actor,manager,args.card);if(p==null||!p.token().equals(args.token))throw SeatRegistry.error("row_stale_preview");
                    RowSelectionState selected=selection(actor,manager,card);if(!selected.equals(p.selection()))throw SeatRegistry.error("row_stale_preview");
                    List<RowBatch.Candidate> fresh=candidates(actor,manager,selected,p.endPosition(),p.endUid());
                    clear(actor);List<CinemaSeatTile> newClaims=new ArrayList<>();
                    try{
                        for(RowBatch.Assignment assignment:p.preview().assignments()){
                            CinemaSeatTile seat=CinemaSeatBlock.findSeat(actor.level(),BlockPos.of(assignment.position())).orElseThrow(()->SeatRegistry.error("row_replaced_uid"));
                            if(!seat.seatUid().toString().equals(assignment.seatUid()))throw SeatRegistry.error("row_replaced_uid");
                            if(!seat.canBind(manager.identity))throw SeatRegistry.error("batch_other_controller");
                            if(seat.boundController().isEmpty()){seat.bindController(manager.identity);newClaims.add(seat);}
                        }
                        RowBatch.Committed result=RowBatch.commit(store.registry(),p.preview(),fresh);applied=result.count();store.setDirty();
                    }catch(RuntimeException failure){for(CinemaSeatTile seat:newClaims)seat.unbindController(manager.identity);throw failure;}
                    SeatBindingItem.rowSelection(card,selected.complete());actor.containerMenu.broadcastChanges();message="mycinema.seats.batch_applied";
                }
                default->throw SeatRegistry.error("batch_invalid");
            }
        }catch(RuntimeException failure){message=notice(failure);}
        send(actor,manager,card,packet.request(),false,message,applied);
    }
    private RowBatchController(){}
}
