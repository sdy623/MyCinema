package local.cinemamanager.seats;

import com.google.gson.*;
import io.netty.buffer.ByteBuf;
import local.cinemamanager.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.*;

/** Owner-only, small paged protocol. Every mutation compares the exact registry or guide revision. */
public final class SeatNetwork {
    public static final int PAGE_SIZE=6;
    private static final Map<UUID,Long> LAST=new HashMap<>();
    private static final Map<UUID,Long> LAST_BITS=new HashMap<>();
    public static final class Args {
        public Long revision;
        public String id="",hall="",zone="",status="AVAILABLE",card="";
        public int row=1,number=1,page,filterRow,direction=1,end;
        public String filterZone="";
        public boolean target,confirmed;
        public long position;
        public GuidePreset guide;
    }
    public record Hall(String id,String name){}
    public record Data(String kind,long revision,boolean editable,int page,int pages,List<Hall> halls,List<SeatRegistry.Seat> seats,
            List<SeatRegistry.Count> counts,Map<String,String> diagnostics,SeatRegistry.Seat selected,boolean target,long position,String hall,String filterZone,int filterRow,GuidePreset guide,String card,boolean continuousConfigured,ContinuousSeatState continuous,String notice){}
    public record Request(BlockPos source,String identity,String request,String action,String json) implements CustomPacketPayload {
        public static final Type<Request> TYPE=new Type<>(CinemaManager.id("seat_request"));
        public static final StreamCodec<ByteBuf,Request> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,Request::source,ByteBufCodecs.stringUtf8(36),Request::identity,ByteBufCodecs.stringUtf8(36),Request::request,ByteBufCodecs.stringUtf8(24),Request::action,ByteBufCodecs.stringUtf8(8192),Request::json,Request::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record View(BlockPos source,String identity,String request,boolean open,String json) implements CustomPacketPayload {
        public static final Type<View> TYPE=new Type<>(CinemaManager.id("seat_view"));
        public static final StreamCodec<ByteBuf,View> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,View::source,ByteBufCodecs.stringUtf8(36),View::identity,ByteBufCodecs.stringUtf8(36),View::request,ByteBufCodecs.BOOL,View::open,ByteBufCodecs.stringUtf8(65536),View::json,View::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public static void register(RegisterPayloadHandlersEvent event){var registrar=event.registrar("mycinema-seats-4-fractional-a1");registrar.playToServer(Request.TYPE,Request.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer actor)apply(actor,p);}));registrar.playToClient(View.TYPE,View.CODEC,(p,c)->c.enqueueWork(()->local.cinemamanager.client.seats.SeatsScreen.receive(p)));}
    public static boolean allowed(ServerPlayer actor,ManagerTile manager){return manager.getLevel()!=null&&manager.getLevel().hasChunkAt(manager.getBlockPos())&&manager.getLevel().getBlockEntity(manager.getBlockPos())==manager&&(manager.controlAllowed(actor)||manager.permits(actor,Double.POSITIVE_INFINITY)&&SeatBindingItem.boundTo(actor,manager));}
    private static boolean accept(ServerPlayer actor){long now=actor.getServer().getTickCount(),last=LAST.getOrDefault(actor.getUUID(),Long.MIN_VALUE);if(last!=Long.MIN_VALUE&&now>=last&&now-last<2)return false;LAST.put(actor.getUUID(),now);if(LAST.size()>128)LAST.entrySet().removeIf(e->now<e.getValue()||now-e.getValue()>400);return true;}
    public static Args parse(String raw){
        try{JsonObject object=JsonParser.parseString(raw).getAsJsonObject();Args args=SeatRegistry.JSON.fromJson(object,Args.class);if(object.has("revision")){JsonElement value=object.get("revision");if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw SeatRegistry.error("stale");args.revision=value.getAsBigDecimal().longValueExact();}
            for(String key:List.of("row","number","page","filterRow","direction","end"))if(object.has(key)){JsonElement value=object.get(key);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw SeatRegistry.error("invalid");value.getAsBigDecimal().intValueExact();}if(object.has("position"))object.get("position").getAsBigDecimal().longValueExact();if(object.has("guide")&&!object.get("guide").isJsonNull())for(String key:List.of("firstRow","lastRow","seat"))object.getAsJsonObject("guide").get(key).getAsBigDecimal().intValueExact();
            args.card=args.card==null?"":args.card;if(!args.card.isEmpty())SeatRegistry.uuid(args.card);args.id=args.id==null?"":args.id;args.hall=args.hall==null?"":args.hall;args.zone=args.zone==null?"":args.zone;args.filterZone=SeatRegistry.label(args.filterZone,32,true);if(args.filterRow<0||args.filterRow>SeatRegistry.MAX_NUMBER)throw SeatRegistry.error("invalid");return args;
        }catch(RuntimeException invalid){throw SeatRegistry.error("invalid");}
    }
    private static long revision(Args args){if(args.revision==null)throw SeatRegistry.error("stale");return args.revision;}
    public static void open(ServerPlayer actor,ManagerTile manager,BlockPos target,ItemStack card){if(!allowed(actor,manager))return;Args args=new Args();args.card=SeatBindingItem.card(card);args.target=target!=null;if(target!=null){args.position=target.asLong();SeatStorage.get((ServerLevel)manager.getLevel(),manager.identity).registry().at(args.position).ifPresent(s->{args.id=s.id();args.hall=s.hall();});}send(actor,manager,args,"",true,"");}
    /** Called only for the clicked existing block; the server never scans adjacent seats. */
    static ContinuousSeatState.Bound bindContinuous(ServerPlayer actor,ManagerTile manager,ItemStack card,BlockPos target){
        if(!allowed(actor,manager)||SeatBindingItem.held(actor,manager,SeatBindingItem.card(card))!=card)throw SeatRegistry.error("permission");
        ContinuousSeatState state=SeatBindingItem.continuous(card,actor,manager);if(state==null)throw SeatRegistry.error("continuous_invalid");
        SeatStorage store=SeatStorage.get((ServerLevel)manager.getLevel(),manager.identity);store.requireEditable();
        if(manager.state.halls.stream().noneMatch(h->h.id.equals(state.hall())))throw SeatRegistry.error("hall_missing");
        requireTarget(actor,target);
        var physical=CinemaSeatBlock.findSeat(actor.level(),target);
        if(SeatRegistry.physicalBlock(SeatBindings.blockType(actor.level().getBlockState(target)))&&physical.isEmpty())throw SeatRegistry.error("batch_real_chair");
        if(physical.isPresent()&&!physical.get().canBind(manager.identity))throw SeatRegistry.error("batch_other_controller");
        ContinuousSeatState.Bound bound=state.bind(store.registry(),target.asLong(),SeatBindings.blockType(actor.level().getBlockState(target)),physical.map(s->s.seatUid().toString()).orElse(""),GuidePreset.RowScheme.NUMERIC);
        if(bound.created()){
            physical.ifPresent(s->s.bindController(manager.identity));
            SeatBindingItem.continuous(card,bound.state());store.setDirty();actor.containerMenu.broadcastChanges();
            actor.displayClientMessage(Component.translatable(bound.state().active()?"mycinema.seats.continuous_bound":"mycinema.seats.continuous_bound_end",state.zone(),state.row(),state.next(),bound.state().next()),true);
        }else actor.displayClientMessage(Component.translatable("mycinema.seats.continuous_skip",state.row(),state.next()),true);
        return bound;
    }
    private static void requireTarget(ServerPlayer actor,BlockPos target){
        if(!actor.level().hasChunkAt(target)||actor.distanceToSqr(Vec3.atCenterOf(target))>64||!actor.level().mayInteract(actor,target)||actor.level().getBlockState(target).isAir())throw SeatRegistry.error("click_seat");
    }
    static void configureContinuous(ServerPlayer actor,ManagerTile manager,Args args,String action){
        if(!allowed(actor,manager))throw SeatRegistry.error("permission");
        ItemStack card=SeatBindingItem.held(actor,manager,args.card);
        if(action.equals("continuous_stop")){SeatBindingItem.continuous(card,null);actor.containerMenu.broadcastChanges();return;}
        SeatStorage store=SeatStorage.get((ServerLevel)manager.getLevel(),manager.identity);store.requireEditable();store.registry().requireRevision(revision(args));
        ContinuousSeatState state;
        if(action.equals("continuous_next")){
            state=SeatBindingItem.continuous(card,actor,manager);if(state==null)throw SeatRegistry.error("continuous_invalid");state=state.nextRow(revision(args));
        }else if(action.equals("continuous_start"))state=ContinuousSeatState.start(actor.getUUID().toString(),manager.identity.toString(),actor.level().dimension().location().toString(),manager.getBlockPos().asLong(),args.hall,args.zone,args.row,args.number,args.direction,args.end,revision(args));
        else throw SeatRegistry.error("invalid");
        final String hall=state.hall();if(manager.state.halls.stream().noneMatch(h->h.id.equals(hall)))throw SeatRegistry.error("hall_missing");
        SeatBindingItem.rowSelection(card,null);RowBatchController.clear(actor);
        SeatBindingItem.continuous(card,state);actor.containerMenu.broadcastChanges();args.hall=hall;args.id="";args.target=false;
    }
    public static void openGuide(ServerPlayer actor,SeatGuideTile guide){if(guide.permits(actor,8))sendGuide(actor,guide,"",true,"");}
    public static void apply(ServerPlayer actor,Request packet){
        try{SeatRegistry.uuid(packet.request());}catch(RuntimeException invalid){return;}
        if(!actor.level().hasChunkAt(packet.source())||!accept(actor))return;
        if(actor.level().getBlockEntity(packet.source()) instanceof SeatGuideTile guide){applyGuide(actor,guide,packet);return;}
        if(!(actor.level().getBlockEntity(packet.source()) instanceof ManagerTile manager)||!manager.identity.toString().equals(packet.identity())||!allowed(actor,manager))return;
        if(packet.action().startsWith("row_batch_")){RowBatchController.apply(actor,manager,packet);return;}
        Args args;try{args=parse(packet.json());}catch(RuntimeException invalid){return;}String notice="";SeatStorage store=SeatStorage.get((ServerLevel)manager.getLevel(),manager.identity);SeatRegistry registry=store.registry();
        try{
            switch(packet.action()){
                case "view"->{}
                case "save"->{
                    store.requireEditable();registry.requireRevision(revision(args));
                    if(manager.state.halls.stream().noneMatch(h->h.id.equals(args.hall)))throw SeatRegistry.error("hall_missing");
                    long position;String block;
                    if(args.id.isEmpty()){
                        if(!args.target)throw SeatRegistry.error("click_seat");BlockPos target=BlockPos.of(args.position);
                        requireTarget(actor,target);position=args.position;block=SeatBindings.blockType(actor.level().getBlockState(target));
                    }else {SeatRegistry.Seat old=registry.find(args.id);position=old.position();block=old.block();}
                    SeatRegistry.Seat saved;
                    if(args.id.isEmpty()){
                        var physical=CinemaSeatBlock.findSeat(actor.level(),BlockPos.of(position));
                        if(SeatRegistry.physicalBlock(block)&&physical.isEmpty())throw SeatRegistry.error("batch_real_chair");
                        if(physical.isPresent()&&!physical.get().canBind(manager.identity))throw SeatRegistry.error("batch_other_controller");
                        saved=registry.put(revision(args),args.id,args.hall,args.zone,args.row,args.number,position,SeatRegistry.Status.valueOf(args.status),block,physical.map(s->s.seatUid().toString()).orElse(""),GuidePreset.RowScheme.NUMERIC);
                        physical.ifPresent(s->s.bindController(manager.identity));
                    }else saved=registry.put(revision(args),args.id,args.hall,args.zone,args.row,args.number,position,SeatRegistry.Status.valueOf(args.status),block);
                    args.id=saved.id();args.position=position;store.setDirty();notice="mycinema.seats.saved";
                }
                case "continuous_start","continuous_next","continuous_stop"->{configureContinuous(actor,manager,args,packet.action());notice=packet.action().equals("continuous_stop")?"mycinema.seats.continuous_stopped":"mycinema.seats.continuous_started";}
                case "delete"->{store.requireEditable();if(!args.confirmed)throw SeatRegistry.error("confirm_delete");SeatRegistry.Seat removed=registry.find(args.id);BlockPos removedPos=BlockPos.of(removed.position());
                    if(!removed.seatUid().isEmpty()&&!actor.level().hasChunkAt(removedPos))throw SeatRegistry.error("row_unloaded");registry.remove(revision(args),args.id);
                    if(!removed.seatUid().isEmpty()&&actor.level().getBlockEntity(removedPos) instanceof CinemaSeatTile tile&&tile.seatUid().toString().equals(removed.seatUid()))tile.unbindController(manager.identity);
                    args.id="";args.target=false;store.setDirty();notice="mycinema.seats.deleted";}
                default->throw SeatRegistry.error("invalid");
            }
        }catch(RuntimeException error){notice=notice(error);}
        send(actor,manager,args,packet.request(),false,notice);
    }
    private static void applyGuide(ServerPlayer actor,SeatGuideTile guide,Request packet){
        if(!guide.identity.toString().equals(packet.identity())||!guide.permits(actor,8))return;String notice="";
        try{guide.requireEditor(actor);Args args=parse(packet.json());switch(packet.action()){
            case "view"->{}
            case "guide_save"->{if(args.guide==null)throw SeatRegistry.error("invalid_guide");guide.update(actor,revision(args),args.guide);notice="mycinema.seats.saved";}
            case "guide_copy"->{guide.copyTo(actor,revision(args));notice="mycinema.seats.copied";}
            case "guide_bits"->{if(revision(args)!=guide.revision)throw SeatRegistry.error("stale");long now=actor.getServer().getTickCount();Long last=LAST_BITS.get(actor.getUUID());if(last!=null&&now>=last&&now-last<20)throw SeatRegistry.error("bits_wait");if(actor.getInventory().getFreeSlot()<0)throw SeatRegistry.error("bits_space");var item=ChiselsBitsBridge.create(actor,guide.preset);if(!actor.getInventory().add(item))throw SeatRegistry.error("bits_space");actor.containerMenu.broadcastChanges();LAST_BITS.put(actor.getUUID(),now);if(LAST_BITS.size()>128)LAST_BITS.entrySet().removeIf(e->now<e.getValue()||now-e.getValue()>400);notice="mycinema.seats.bits_created";}
            default->throw SeatRegistry.error("invalid");
        }}catch(RuntimeException error){notice=notice(error);}sendGuide(actor,guide,packet.request(),false,notice);
    }
    private static String notice(RuntimeException error){return error.getMessage()!=null&&error.getMessage().startsWith("mycinema.seats.")?error.getMessage():"mycinema.seats.invalid";}
    private static void sendGuide(ServerPlayer actor,SeatGuideTile guide,String request,boolean open,String notice){send(actor,new View(guide.getBlockPos(),guide.identity.toString(),request,open,SeatRegistry.JSON.toJson(new Data("guide",guide.revision,true,0,1,List.of(),List.of(),List.of(),Map.of(),null,false,0,"","",0,guide.preset,"",false,null,notice))));}
    private static void send(ServerPlayer actor,ManagerTile manager,Args args,String request,boolean open,String notice){
        if(!allowed(actor,manager))return;SeatStorage storage=SeatStorage.get((ServerLevel)manager.getLevel(),manager.identity);SeatRegistry registry=storage.registry();
        List<Hall> halls=manager.state.halls.stream().map(h->new Hall(h.id,h.name)).toList();String hall=halls.stream().anyMatch(h->h.id.equals(args.hall))?args.hall:halls.isEmpty()?"":halls.getFirst().id;
        List<SeatRegistry.Seat> all=registry.seats().stream().filter(s->s.hall().equals(hall)&&(args.filterZone.isEmpty()||s.zone().equalsIgnoreCase(args.filterZone))&&(args.filterRow==0||args.filterRow==s.row())).sorted(Comparator.comparing(SeatRegistry.Seat::zone).thenComparingInt(SeatRegistry.Seat::row).thenComparingInt(SeatRegistry.Seat::number)).toList();
        int pages=Math.max(1,(all.size()+PAGE_SIZE-1)/PAGE_SIZE),page=Math.clamp(args.page,0,pages-1);List<SeatRegistry.Seat> rows=all.stream().skip((long)page*PAGE_SIZE).limit(PAGE_SIZE).toList();
        List<SeatRegistry.Count> counts=registry.counts().stream().filter(c->c.hall().equals(hall)&&((c.zone().isEmpty()&&c.row()==0)||(!args.filterZone.isEmpty()&&c.zone().equalsIgnoreCase(args.filterZone)&&(c.row()==0||c.row()==args.filterRow)))).limit(3).toList();
        SeatRegistry.Seat selected=registry.seats().stream().filter(s->s.id().equals(args.id)).findFirst().orElse(null);
        if(!storage.editable())notice="mycinema.seats.invalid_saved";
        Map<String,String> diagnostics=new LinkedHashMap<>();for(SeatRegistry.Seat seat:rows)diagnostics.put(seat.id(),SeatBindings.status(actor.level(),seat));if(selected!=null)diagnostics.put(selected.id(),SeatBindings.status(actor.level(),selected));
        String card="";boolean configured=false;ContinuousSeatState continuous=null;
        if(!args.card.isEmpty())try{ItemStack held=SeatBindingItem.held(actor,manager,args.card);card=args.card;configured=SeatBindingItem.configured(held);continuous=SeatBindingItem.continuous(held,actor,manager);}catch(RuntimeException invalid){if(notice.isEmpty())notice=notice(invalid);}
        Data data=new Data("registry",registry.revision(),storage.editable(),page,pages,halls,rows,counts,diagnostics,selected,args.target,args.position,hall,args.filterZone,args.filterRow,null,card,configured,continuous,notice);
        send(actor,new View(manager.getBlockPos(),manager.identity.toString(),request,open,SeatRegistry.JSON.toJson(data)));
    }
    private static void send(ServerPlayer actor,View view){PacketDistributor.sendToPlayer(actor,view);}
    private SeatNetwork(){}
}
