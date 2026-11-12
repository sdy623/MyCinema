package local.cinemamanager.advertising;

import local.cinemamanager.*;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.projection.CinemaProjectorPartTile;
import local.cinemamanager.projection.ProjectorStructure;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

/** Select an existing display nearby, then bind a separately owned LED nearby. No playback controls. */
public final class DisplayLinkingCard extends Item {
    public DisplayLinkingCard(Properties properties){super(properties);}
    @Override public InteractionResult onItemUseFirst(net.minecraft.world.item.ItemStack stack,UseOnContext context){
        if(!context.getLevel().hasChunkAt(context.getClickedPos()))return InteractionResult.PASS;
        var tile=context.getLevel().getBlockEntity(context.getClickedPos());
        return tile instanceof DisplayTile||tile instanceof CinemaProjectorPartTile||tile instanceof InfoTile info&&info.isAdvertising()||tile instanceof AdvertisingPartTile?useOn(context):InteractionResult.PASS;
    }
    @Override public InteractionResult useOn(UseOnContext context){
        if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;
        if(!(context.getPlayer() instanceof ServerPlayer actor))return InteractionResult.FAIL;
        if(!context.getLevel().hasChunkAt(context.getClickedPos()))return InteractionResult.FAIL;
        var target=context.getLevel().getBlockEntity(context.getClickedPos());
        if(target instanceof CinemaProjectorPartTile){
            var anchor=ProjectorStructure.resolve(context.getLevel(),context.getClickedPos());
            if(anchor!=null&&context.getLevel().hasChunkAt(anchor))target=context.getLevel().getBlockEntity(anchor);
        }
        if(target instanceof DisplayTile source){MirrorLinks.select(actor,source,actor.isShiftKeyDown());return InteractionResult.SUCCESS;}
        InfoTile board=target instanceof InfoTile t?t:target instanceof AdvertisingPartTile part?part.root():null;
        if(board!=null&&board.isAdvertising()){
            var data=context.getItemInHand().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            MirrorLinks.bind(actor,board,data.hasUUID("mirror_selection")?data.getUUID("mirror_selection"):null);
            return InteractionResult.SUCCESS;
        }
        actor.displayClientMessage(net.minecraft.network.chat.Component.translatable("mycinema.mirror.select_source"),true);
        return InteractionResult.FAIL;
    }
}
