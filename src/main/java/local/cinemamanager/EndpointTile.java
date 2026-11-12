package local.cinemamanager;
import local.cinemamanager.model.ManagerState;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;
public final class EndpointTile extends OwnedTile {
    public BlockPos manager;
    public UUID managerIdentity;
    public String hallId = "";
    private boolean refreshNeighbors = true;
    public EndpointTile(BlockPos p, BlockState s) { super(CinemaManager.ENDPOINT_TILE.get(), p, s); }
    public void tick() {
        if (level == null || level.getGameTime() % 10 != 0) return;
        ManagerState.Hall hall = null;
        if (manager != null && level.hasChunkAt(manager) && level.getBlockEntity(manager) instanceof ManagerTile tile && tile.identity.equals(managerIdentity))
            try { hall = tile.state.hall(hallId); } catch (IllegalArgumentException ignored) { }
        EndpointBlock output=(EndpointBlock)getBlockState().getBlock();
        int signal = output.light?ManagerState.outputPower(output.group,hall,hall!=null):ManagerState.outputPower(false,hall,hall!=null);
        if (refreshNeighbors || getBlockState().getValue(EndpointBlock.POWER) != signal) {
            refreshNeighbors = false;
            level.setBlock(worldPosition, getBlockState().setValue(EndpointBlock.POWER, signal), 3);
            level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
            for (Direction d : Direction.values()) level.updateNeighborsAt(worldPosition.relative(d), getBlockState().getBlock());
            setChanged();
        }
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider regs) { super.saveAdditional(tag, regs); if (manager != null && managerIdentity != null) { tag.putLong("manager", manager.asLong()); tag.putUUID("manager_id", managerIdentity); tag.putString("hall", hallId); } }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider regs) { super.loadAdditional(tag, regs); refreshNeighbors = true; manager = tag.contains("manager") ? BlockPos.of(tag.getLong("manager")) : null; managerIdentity = tag.hasUUID("manager_id") ? tag.getUUID("manager_id") : null; hallId = tag.getString("hall"); }
}
