package local.cinemamanager.access;

import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import java.util.*;
import local.cinemamanager.CinemaManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Small allowlisted protocol; client packets never supply a card token or controller binding. */
public final class AccessNetwork {
    public static final Gson JSON=new Gson();
    private static final Map<UUID,Long> REQUESTS=new HashMap<>();
    public record Edit(BlockPos pos,String identity,String revision,String action,String target,String role) implements CustomPacketPayload {
        public static final Type<Edit> TYPE=new Type<>(CinemaManager.id("access_edit"));
        public static final StreamCodec<ByteBuf,Edit> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,Edit::pos,ByteBufCodecs.stringUtf8(36),Edit::identity,ByteBufCodecs.stringUtf8(36),Edit::revision,ByteBufCodecs.stringUtf8(12),Edit::action,ByteBufCodecs.stringUtf8(36),Edit::target,ByteBufCodecs.stringUtf8(12),Edit::role,Edit::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record View(BlockPos pos,String identity,String json,boolean open) implements CustomPacketPayload {
        public static final Type<View> TYPE=new Type<>(CinemaManager.id("access_view"));
        public static final StreamCodec<ByteBuf,View> CODEC=StreamCodec.composite(BlockPos.STREAM_CODEC,View::pos,ByteBufCodecs.stringUtf8(36),View::identity,ByteBufCodecs.stringUtf8(65536),View::json,ByteBufCodecs.BOOL,View::open,View::new);
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    public record Person(String id,String name,String role,boolean online){}
    public record Data(String revision,String required,boolean paired,String door,String notice,List<Person> people){}
    public static void register(PayloadRegistrar registrar){
        registrar.playToServer(Edit.TYPE,Edit.CODEC,(packet,context)->context.enqueueWork(()->{if(context.player() instanceof ServerPlayer actor)apply(actor,packet);}));
        registrar.playToClient(View.TYPE,View.CODEC,(packet,context)->context.enqueueWork(()->local.cinemamanager.client.access.StaffAccessScreen.open(packet)));
    }
    public static void open(ServerPlayer actor,StaffReaderTile reader){reader.requireEditor(actor);send(actor,reader,"",true);}
    private static ItemStack blank(ServerPlayer actor){
        for(int slot=0;slot<actor.getInventory().getContainerSize();slot++){ItemStack stack=actor.getInventory().getItem(slot);if(stack.is(AccessModule.CARD.get())&&stack.getCount()==1&&StaffCardItem.token(stack)==null)return stack;}
        throw new IllegalArgumentException("mycinema.access.blank_required");
    }
    public static void apply(ServerPlayer actor,Edit packet){
        if(!StaffReaderTile.real(actor)||!local.cinemamanager.panels.PanelNetwork.acceptAt(REQUESTS,actor.getUUID(),actor.getServer().getTickCount(),4)
                ||!actor.level().hasChunkAt(packet.pos())||!(actor.level().getBlockEntity(packet.pos()) instanceof StaffReaderTile reader)||!reader.identity.toString().equals(packet.identity()))return;
        String notice="";
        try{
            reader.requireEditor(actor);
            if(!packet.action().equals("refresh"))reader.requireRevision(packet.revision());
            var registry=StaffAccessRegistry.get(actor.serverLevel());
            switch(packet.action()){
                case "refresh" -> {}
                case "role" -> {reader.changeRole(actor,packet.revision(),StaffRole.valueOf(packet.role()));notice="mycinema.access.role_changed";}
                case "revoke" -> {UUID target=UUID.fromString(packet.target());var scope=reader.currentScope();if(scope==null)throw new IllegalArgumentException("mycinema.access.stale");registry.cards.revokeCinema(scope,target);registry.setDirty();reader.edited();notice="mycinema.access.revoked";}
                case "issue" -> {
                    UUID target=UUID.fromString(packet.target());ServerPlayer recipient=actor.getServer().getPlayerList().getPlayer(target);
                    if(!StaffReaderTile.real(recipient))throw new IllegalArgumentException("mycinema.access.online_required");
                    StaffRole role=StaffRole.valueOf(packet.role()); // Issuance is cinema-wide; this reader's entry requirement is independent.
                    var scope=reader.currentScope();if(scope==null)throw new IllegalArgumentException("mycinema.access.stale");
                    ItemStack card=blank(actor);var grant=registry.cards.issue(target,actor.getUUID(),scope,role);registry.setDirty();
                    StaffCardItem.stamp(card,grant,recipient.getGameProfile().getName());actor.getInventory().setChanged();actor.containerMenu.broadcastChanges();reader.edited();notice="mycinema.access.issued";
                }
                default -> throw new IllegalArgumentException("mycinema.access.invalid");
            }
        }catch(RuntimeException denied){notice=denied.getMessage()!=null&&denied.getMessage().startsWith("mycinema.access.")?denied.getMessage():"mycinema.access.invalid";}
        // Recheck ownership before disclosing the staff roster or accepting another edit.
        try{reader.requireEditor(actor);send(actor,reader,notice,false);}catch(IllegalArgumentException ignored){}
    }
    private static void send(ServerPlayer actor,StaffReaderTile reader,String notice,boolean open){
        LinkedHashMap<UUID,Person> people=new LinkedHashMap<>();
        for(var grant:StaffAccessRegistry.get(actor.serverLevel()).cards.forCinema(reader.currentScope())){
            var online=actor.getServer().getPlayerList().getPlayer(grant.player());String name=online==null?grant.player().toString():online.getGameProfile().getName();people.merge(grant.player(),new Person(grant.player().toString(),name,grant.role().name(),online!=null),
                    (a,b)->a.role().equals(StaffRole.SERVICE.name())?a:b);
        }
        actor.getServer().getPlayerList().getPlayers().stream().filter(StaffReaderTile::real).sorted(Comparator.comparing(p->p.getGameProfile().getName())).limit(128).forEach(p->people.putIfAbsent(p.getUUID(),new Person(p.getUUID().toString(),p.getGameProfile().getName(),"",true)));
        if(people.size()>256&&notice.isBlank())notice="mycinema.access.roster_limited";
        var data=new Data(reader.editRevision.toString(),reader.required.name(),reader.currentScope()!=null,reader.doorPos==null?"":reader.doorPos.toShortString(),notice,people.values().stream().limit(256).toList());
        PacketDistributor.sendToPlayer(actor,new View(reader.getBlockPos(),reader.identity.toString(),JSON.toJson(data),open));
    }
    private AccessNetwork(){}
}
