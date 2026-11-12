package local.cinemamanager.seats;

import local.cinemamanager.OwnedTile;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;

public final class SeatGuideTile extends OwnedTile {
    public GuidePreset preset=GuidePreset.empty();
    public long revision;
    public SeatGuideTile(BlockPos pos,BlockState state){super(SeatsModule.GUIDE_TILE.get(),pos,state);}
    @Override public boolean onlyOpCanSetNbt(){return true;}
    public void initialize(UUID owner,ItemStack stack){this.owner=owner;identity=UUID.randomUUID();revision=0;preset=GuidePreset.empty();try{String raw=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getString("mycinema_guide");if(!raw.isEmpty()){GuidePreset decoded=SeatRegistry.JSON.fromJson(raw,GuidePreset.class);if(decoded!=null)preset=decoded;}}catch(RuntimeException ignored){}dirty();}
    public void requireEditor(ServerPlayer actor){if(level==null||!level.hasChunkAt(worldPosition)||level.getBlockEntity(worldPosition)!=this||!permits(actor,8))throw SeatRegistry.error("permission");}
    public void update(ServerPlayer actor,long expected,GuidePreset value){requireEditor(actor);if(value==null)throw SeatRegistry.error("invalid_guide");if(expected!=revision||revision==Long.MAX_VALUE)throw SeatRegistry.error("stale");preset=value;revision++;dirty();}
    public void copyTo(ServerPlayer actor,long expected){
        requireEditor(actor);if(expected!=revision)throw SeatRegistry.error("stale");
        ItemStack stack=actor.getMainHandItem().is(SeatsModule.GUIDE_ITEM.get())?actor.getMainHandItem():actor.getOffhandItem();
        if(!stack.is(SeatsModule.GUIDE_ITEM.get()))throw SeatRegistry.error("hold_tags");
        CompoundTag data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();data.putString("mycinema_guide",SeatRegistry.JSON.toJson(preset));stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
    }
    @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries){super.saveAdditional(tag,registries);tag.putLong("guide_revision",revision);tag.putString("guide",SeatRegistry.JSON.toJson(preset));}
    @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries){super.loadAdditional(tag,registries);revision=Math.max(0,tag.getLong("guide_revision"));preset=GuidePreset.empty();try{GuidePreset decoded=SeatRegistry.JSON.fromJson(tag.getString("guide"),GuidePreset.class);if(decoded!=null)preset=decoded;}catch(RuntimeException ignored){}}
}
