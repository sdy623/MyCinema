package local.cinemamanager.projection;

import java.util.UUID;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import me.srrapero720.waterframes.client.display.Display;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.player.ServerMediaPlayer;

/** MyCinema cinema-screen endpoint; decoding and rendering remain in official WaterFrames. */
public final class CinemaProjectorTile extends CinemaDeviceTile {
    private UUID identity = UUID.randomUUID();
    private BlockPos terminalPos;
    private UUID terminalId;
    private ProjectorStyle style = ProjectorStyle.CINEMA;
    private net.minecraft.core.Direction machineFacing = net.minecraft.core.Direction.NORTH;
    private boolean modelCore;
    public float projectionOffsetX;
    public float projectionOffsetY;
    public float projectionOffsetZ;

    public CinemaProjectorTile(BlockPos pos, BlockState state) {
        super(CinemaManager.CINEMA_PROJECTOR_TILE.get(), pos, state);
        if (state.is(CinemaManager.IMAX_GT_PROJECTOR.get())) style = ProjectorStyle.IMAX_GT;
        data.setWidth(42);
    }

    public UUID identity() { return identity; }
    public ProjectorStyle style() { return style; }
    public net.minecraft.core.Direction machineFacing() { return machineFacing; }
    public boolean modelCore() { return modelCore; }
    public void modelCore(boolean value) { modelCore = value; setChanged(); }
    public void initializeStyle(ProjectorStyle style, net.minecraft.core.Direction facing) { this.style = style == null ? ProjectorStyle.CINEMA : style; this.machineFacing = facing == null ? net.minecraft.core.Direction.NORTH : facing; setChanged(); }
    public BlockPos terminalPos() { return terminalPos; }
    public boolean directControlsLocked() {
        if (terminalPos == null || terminalId == null) return false;
        if (getLevel() != null && getLevel().hasChunkAt(terminalPos)
                && getLevel().getBlockEntity(terminalPos) instanceof CinemaTerminalTile terminal
                && terminal.identity().equals(terminalId) && terminal.contains(this))
            return terminal.controlsLinked();
        // A stale, unloaded or non-reciprocal link never grants direct control.
        return true;
    }
    public Component controlLockMessage() { return Component.translatable("message.mycinema.projector.linked"); }

    public boolean linkedTo(CinemaTerminalTile terminal) {
        return terminal != null && terminalPos != null && terminalId != null
                && terminal.getLevel() == getLevel() && terminal.getBlockPos().equals(terminalPos)
                && terminal.identity().equals(terminalId) && terminal.contains(this);
    }

    public CinemaTerminalTile terminal() {
        if (terminalPos == null || terminalId == null || getLevel() == null || !getLevel().hasChunkAt(terminalPos)) return null;
        if (getLevel().getBlockEntity(terminalPos) instanceof CinemaTerminalTile terminal
                && terminal.identity().equals(terminalId) && terminal.contains(this)) return terminal;
        return null;
    }

    public void bind(CinemaTerminalTile terminal) {
        if (terminal == null) {
            // Clear only this endpoint.  The terminal's retained membership is
            // deliberately left in place so every group operation detects the
            // broken reciprocal link and fails atomically instead of silently
            // degrading a reviewed two-projector hall to one projector.
            terminalPos = null;
            terminalId = null;
            setChanged();
            return;
        }
        // A loaded projector may retain an unresolved authority description.
        // Recording it is side-effect free and never loads the target chunk.
        if (terminal.getLevel() == null && getLevel() != null) {
            terminalPos = terminal.getBlockPos().immutable();
            terminalId = terminal.identity();
            setChanged();
            return;
        }
        if (terminal.getLevel() != getLevel() || !terminal.link(this)) return;
        CinemaTerminalTile previous = terminal();
        if (previous != null && previous != terminal) previous.unlink(this);
        terminalPos = terminal.getBlockPos().immutable();
        terminalId = terminal.identity();
        setChanged();
    }

    public void unbind(CinemaTerminalTile terminal) {
        if (terminal != null && terminal.getBlockPos().equals(terminalPos) && terminal.identity().equals(terminalId)) {
            terminal.unlink(this);
            terminalPos = null;
            terminalId = null;
            setChanged();
        }
    }

    public Display activeDisplay() {
        CinemaTerminalTile terminal = terminal();
        return terminal == null ? null : terminal.activeDisplay();
    }

    public Vec3 audioOrigin() {
        CinemaTerminalTile terminal = terminal();
        return terminal == null ? getBlockPos().getCenter() : terminal.audioOrigin();
    }

    @Override public Display requestDisplay() {
        CinemaTerminalTile terminal = terminal();
        return terminal == null ? super.requestDisplay() : terminal.requestDisplay();
    }

    @Override public MediaPlayer follower() {
        return null;
    }

    @Override public ServerMediaPlayer clock() {
        return null;
    }

    @Override public void reload() {
        CinemaTerminalTile terminal = terminal();
        if (terminal == null) super.reload(); else terminal.reload();
    }

    public void reloadMedia() { reload(); }
    public boolean isValidBlockState(BlockState state) { return state != null && (state.is(CinemaManager.CINEMA_PROJECTOR.get()) || state.is(CinemaManager.IMAX_GT_PROJECTOR.get())); }

    @Override public void openScreen(boolean remote) { if (!directControlsLocked()) super.openScreen(remote); }
    @Override public void setActive(boolean remote, boolean value) { if (!directControlsLocked()) super.setActive(remote, value); }
    @Override public void setMute(boolean remote, boolean value) { if (!directControlsLocked()) super.setMute(remote, value); }
    @Override public void setPause(boolean remote, boolean value) { if (!directControlsLocked()) super.setPause(remote, value); }
    @Override public void setStop(boolean remote) { if (!directControlsLocked()) super.setStop(remote); }
    @Override public void volumeUp(boolean remote) { if (!directControlsLocked()) super.volumeUp(remote); }
    @Override public void volumeDown(boolean remote) { if (!directControlsLocked()) super.volumeDown(remote); }
    @Override public void nextUrl(boolean remote) { if (!directControlsLocked()) super.nextUrl(remote); }
    @Override public void prevUrl(boolean remote) { if (!directControlsLocked()) super.prevUrl(remote); }
    @Override public void loop(boolean remote, boolean value) { if (!directControlsLocked()) super.loop(remote, value); }
    @Override public void seek(boolean remote, long value) { if (!directControlsLocked()) super.seek(remote, value); }
    @Override public void skip(boolean remote, long value) { if (!directControlsLocked()) super.skip(remote, value); }
    @Override public void position(boolean remote,
                                   me.srrapero720.waterframes.common.block.data.types.PositionHorizontal horizontal,
                                   me.srrapero720.waterframes.common.block.data.types.PositionVertical vertical) {
        if (!directControlsLocked()) super.position(remote, horizontal, vertical);
    }

    @Override
    protected void onOwnerChanged() {
        super.onOwnerChanged();
        // Ownership changes must not silently rewrite physical cabling. Linked
        // operations and claims recheck the complete owner set and fail closed.
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putUUID("cinema_projector_id", identity);
        if (terminalPos != null && terminalId != null) {
            tag.putLong("cinema_terminal_pos", terminalPos.asLong());
            tag.putUUID("cinema_terminal_id", terminalId);
        }
        tag.putString("projector_style", style.getSerializedName());
        tag.putString("projector_facing", machineFacing.getName());
        tag.putBoolean("model_core", modelCore);
        tag.putFloat("projection_offset_x", projectionOffsetX); tag.putFloat("projection_offset_y", projectionOffsetY); tag.putFloat("projection_offset_z", projectionOffsetZ);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        if (tag.hasUUID("cinema_projector_id")) identity = tag.getUUID("cinema_projector_id");
        terminalPos = tag.contains("cinema_terminal_pos") ? BlockPos.of(tag.getLong("cinema_terminal_pos")) : null;
        terminalId = tag.hasUUID("cinema_terminal_id") ? tag.getUUID("cinema_terminal_id") : null;
        style = tag.contains("projector_style") ? ProjectorStyle.parse(tag.getString("projector_style"))
                : (getBlockState().is(CinemaManager.IMAX_GT_PROJECTOR.get()) ? ProjectorStyle.IMAX_GT : ProjectorStyle.CINEMA);
        machineFacing = net.minecraft.core.Direction.byName(tag.getString("projector_facing")); if (machineFacing == null || !machineFacing.getAxis().isHorizontal()) machineFacing = net.minecraft.core.Direction.NORTH;
        modelCore = tag.getBoolean("model_core"); projectionOffsetX = tag.getFloat("projection_offset_x"); projectionOffsetY = tag.getFloat("projection_offset_y"); projectionOffsetZ = tag.getFloat("projection_offset_z");
    }
}
