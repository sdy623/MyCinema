package local.cinemamanager.automation;

import java.util.Objects;
import java.util.UUID;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.media.CinemaSources;
import local.cinemamanager.policy.CinemaPermissions;
import local.cinemamanager.theater.CinemaTerminalTile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.GameType;

/** Persisted automation authority owned by MyCinema. Device execution is attached separately. */
public final class CinemaAutomation {
    public static final int RANGE = 8;
    public enum Action { PLAY_NEW_MEDIA, STOP }

    public record Grant(UUID terminalIdentity, UUID controllerIdentity, UUID owner,
                        BlockPos controllerPos, GameType mode) {
        public Grant {
            Objects.requireNonNull(terminalIdentity, "terminalIdentity");
            Objects.requireNonNull(controllerIdentity, "controllerIdentity");
            Objects.requireNonNull(owner, "owner");
            controllerPos = Objects.requireNonNull(controllerPos, "controllerPos").immutable();
            if (mode == null || mode == GameType.SPECTATOR) throw new IllegalArgumentException("mode");
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("terminal", terminalIdentity);
            tag.putUUID("controller", controllerIdentity);
            tag.putUUID("owner", owner);
            tag.putLong("pos", controllerPos.asLong());
            tag.putString("mode", mode.getName());
            return tag;
        }

        public static Grant load(CompoundTag tag) {
            if (tag == null || !tag.hasUUID("terminal") || !tag.hasUUID("controller") || !tag.hasUUID("owner")
                    || !tag.contains("pos", Tag.TAG_LONG) || !tag.contains("mode", Tag.TAG_STRING)) return null;
            try {
                GameType mode = GameType.byName(tag.getString("mode"), null);
                if (mode == null || mode == GameType.SPECTATOR) return null;
                return new Grant(tag.getUUID("terminal"), tag.getUUID("controller"), tag.getUUID("owner"),
                        BlockPos.of(tag.getLong("pos")), mode);
            } catch (RuntimeException malformed) {
                return null;
            }
        }

        public boolean matches(UUID terminal, UUID controller, UUID expectedOwner, BlockPos position) {
            return terminalIdentity.equals(terminal) && controllerIdentity.equals(controller)
                    && owner.equals(expectedOwner) && controllerPos.equals(position);
        }
    }

    public static String grant(CinemaTerminalTile terminal, net.minecraft.server.level.ServerPlayer actor,
                               BlockPos controllerPos, UUID controllerIdentity) {
        if (terminal == null || actor == null || controllerPos == null || controllerIdentity == null)
            return "自动授权参数不完整";
        if (!(terminal.getLevel() instanceof net.minecraft.server.level.ServerLevel level)
                || actor.level() != level || actor.isSpectator() || !level.getServer().isSameThread()
                || !level.hasChunkAt(controllerPos)
                || !(level.getBlockEntity(controllerPos) instanceof ManagerTile manager)
                || !controllerIdentity.equals(manager.identity)) return "控制中心身份或范围无效";
        if (!terminal.ownedBy(actor.getUUID()) || !manager.permits(actor, RANGE)
                || !terminal.allows(actor) || !CinemaPermissions.canBindRemote(actor)
                || !CinemaPermissions.canInteractRemote(actor)) return "需要真实影厅所有者在馆长终端 8 格内授权";
        var mode = actor.gameMode.getGameModeForPlayer();
        if (!CinemaPermissions.canAutomateTheater(actor.getUUID(), mode, terminal.getBlockState().getBlock(), "", false))
            return "当前服务器配置/离线权限不允许影院自动控制";
        terminal.automationGrant(new Grant(terminal.identity(), controllerIdentity, actor.getUUID(), controllerPos, mode));
        return null;
    }

    public static String execute(CinemaTerminalTile terminal, BlockPos controllerPos, UUID controllerIdentity,
                                 UUID owner, Action action, String source) {
        if (terminal == null || controllerPos == null || controllerIdentity == null || owner == null || action == null)
            return "automation_invalid";
        Grant grant = terminal.automationGrant();
        if (grant == null || !grant.matches(terminal.identity(), controllerIdentity, owner, controllerPos)
                || !terminal.ownedBy(owner)) return "automation_grant_revoked";
        var controller = new CinemaSources.ControllerRef(controllerPos, controllerIdentity);
        if (action == Action.PLAY_NEW_MEDIA) {
            var result = CinemaSources.prepareDelegated(terminal, owner, controller,
                    new CinemaSources.SourceRef(source, 0), terminal.mediaEpoch(), null);
            if (result.error() != null) return result.error();
            terminal.data.active = true;
            terminal.data.paused = false;
            if (terminal.clock() != null) terminal.clock().pause(false);
            terminal.libraryStarted();
            terminal.setChanged();
            return null;
        }
        return CinemaSources.haltDelegated(terminal, owner, controller, terminal.mediaEpoch()).error();
    }

    private CinemaAutomation() {}
}
