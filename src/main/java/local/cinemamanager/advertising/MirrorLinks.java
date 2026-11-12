package local.cinemamanager.advertising;

import local.cinemamanager.*;
import local.cinemamanager.panels.PanelGroup;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.mirror.CinemaMirrors;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.util.*;

/** Short-lived server-owned selection receipts. Item NBT never supplies source authority. */
public final class MirrorLinks {
    private record Selection(UUID actor,CinemaMirrors.BindingRef ref,long until){}
    private static final Map<MinecraftServer,LinkedHashMap<UUID,Selection>> SELECTIONS=new WeakHashMap<>();
    private MirrorLinks(){}
    private static ItemStack card(ServerPlayer actor){
        if(actor.getMainHandItem().getItem() instanceof DisplayLinkingCard)return actor.getMainHandItem();
        return actor.getOffhandItem().getItem() instanceof DisplayLinkingCard?actor.getOffhandItem():ItemStack.EMPTY;
    }
    public static void select(ServerPlayer actor,DisplayTile source,boolean revoke){
        if(card(actor).isEmpty())return;
        var inspection=CinemaMirrors.inspect(source);
        if(inspection==null){message(actor,"source_unavailable");return;}
        if(revoke){MirrorNetwork.offer(actor,source,inspection.mirrorIdentity(),true);return;}
        var result=CinemaMirrors.authorize(source,actor,inspection.mirrorIdentity());
        if(result.error()!=null){
            if(result.error().equals("unclaimed")&&!(source instanceof CinemaDeviceTile))MirrorNetwork.offer(actor,source,inspection.mirrorIdentity(),false);
            else message(actor,"denied");
            return;
        }
        remember(actor,result.binding());
    }
    static void remember(ServerPlayer actor,CinemaMirrors.BindingRef ref){
        ItemStack card=card(actor);if(card.isEmpty()||ref==null)return;
        long now=actor.server.getTickCount();var all=SELECTIONS.computeIfAbsent(actor.server,s->new LinkedHashMap<>());
        all.entrySet().removeIf(e->e.getValue().until()<now||e.getValue().actor().equals(actor.getUUID()));
        while(all.size()>=128)all.remove(all.keySet().iterator().next());
        UUID token=UUID.randomUUID();all.put(token,new Selection(actor.getUUID(),ref,now+12000));
        CompoundTag tag=new CompoundTag();tag.putUUID("mirror_selection",token);card.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
        message(actor,"selected");
    }
    public static void bind(ServerPlayer actor,InfoTile board,UUID token){
        if(card(actor).isEmpty())return;
        Selection receipt=token==null?null:SELECTIONS.getOrDefault(actor.server,new LinkedHashMap<>()).get(token);
        if(receipt==null||!receipt.actor().equals(actor.getUUID())||receipt.until()<actor.server.getTickCount()){
            message(actor,"selection_expired");return;
        }
        try{
            board.bindMirror(actor,PanelGroup.of(board).signature(),receipt.ref());message(actor,"bound");
        }catch(IllegalArgumentException error){actor.displayClientMessage(Component.translatable(error.getMessage()),true);}
    }
    static void message(ServerPlayer actor,String key){actor.displayClientMessage(Component.translatable("mycinema.mirror."+key),false);}
}
