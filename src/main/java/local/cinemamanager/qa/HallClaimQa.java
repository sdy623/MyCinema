package local.cinemamanager.qa;

import java.util.*;
import local.cinemamanager.*;
import local.cinemamanager.access.StaffReaderTile;
import local.cinemamanager.library.CatalogStorage;
import local.cinemamanager.library.HallAuthorization;
import local.cinemamanager.library.LocalProgrammeConsents;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.policy.CinemaPermissions;
import local.cinemamanager.CinemaDisplays;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.media.MediaSource;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Native S7 fixture only. Ownership claim and both grants must use the real owner UI. */
@EventBusSubscriber(modid = CinemaManager.ID)
public final class HallClaimQa {
    private static final BlockPos MANAGER = new BlockPos(64, -60, 28);
    private static final BlockPos TERMINAL = new BlockPos(64, -60, 48);
    private static final List<BlockPos> PROJECTORS = List.of(new BlockPos(62, -60, 52), new BlockPos(66, -60, 52));
    private static final Vec3 MANAGER_STAND = new Vec3(64.5, -60, 26.5);
    private static final Vec3 TERMINAL_STAND = new Vec3(64.5, -60, 46.5);
    private static final String NAME = "Hall Claim QA Cinema";
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private HallClaimQa() {}

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        if (!Boolean.getBoolean("mycinema.qa")) return;
        event.getDispatcher().register(Commands.literal("mycinemahallclaimqa").requires(s -> s.hasPermission(4))
                .then(Commands.literal("setup").executes(c -> { setup(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("open").executes(c -> { open(c.getSource().getPlayerOrException()); return 1; }))
                .then(Commands.literal("inspect").executes(c -> { inspect(c.getSource().getPlayerOrException()); return 1; })));
    }

    private static ServerLevel world(ServerPlayer actor) {
        var level = actor.serverLevel();
        if (!Boolean.getBoolean("mycinema.qa") || !level.getServer().isSameThread()
                || !actor.hasPermissions(4) || !StaffReaderTile.real(actor)
                || level.getServer().getPlayerList().getPlayer(actor.getUUID()) != actor
                || !level.getServer().getWorldData().getLevelName().toLowerCase(Locale.ROOT).contains("qa")
                || !level.dimension().equals(Level.OVERWORLD))
            throw new IllegalStateException("Hall-claim fixture requires an independent QA overworld and its actual online OP4 player");
        return level;
    }

    private static String media(ServerPlayer actor) {
        String source = MediaSource.normalize(System.getProperty("mycinema.qaMedia", ""));
        if (!MediaSource.isNormalizedLocal(source) || !CinemaPermissions.canSave(actor, source))
            throw new IllegalStateException("Configure mycinema.qaMedia with an allowed synthetic local media file before setup");
        return source;
    }

    /** The complete bounded destination and standing clearance are checked before any write. */
    private static Map<BlockPos, BlockState> targets() {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (int x = 60; x <= 68; x++) for (int z = 26; z <= 54; z++)
            result.put(new BlockPos(x, -61, z), Blocks.SMOOTH_STONE.defaultBlockState());
        result.put(MANAGER, CinemaManager.MANAGER.get().defaultBlockState());
        result.put(TERMINAL, CinemaDisplays.TMS_TERMINAL.get().defaultBlockState());
        for (BlockPos pos : PROJECTORS) result.put(pos, CinemaDisplays.CINEMA_PROJECTOR.get().defaultBlockState());
        return result;
    }

    private static void accessible(ServerLevel level, ServerPlayer actor, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || !level.hasChunkAt(pos) || !level.mayInteract(actor, pos))
            throw new IllegalStateException("Fixture refuses an out-of-bounds, unloaded or protected position at " + pos.toShortString());
    }

    private static Map<BlockPos, BlockState> preflight(ServerLevel level, ServerPlayer actor,
                                                       Map<BlockPos, BlockState> targets) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        for (int x = 60; x <= 68; x++) for (int z = 26; z <= 54; z++) for (int y = -61; y <= -58; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            accessible(level, actor, pos);
            if (level.getBlockEntity(pos) != null)
                throw new IllegalStateException("Fixture refuses an existing block entity at " + pos.toShortString());
            BlockState old = level.getBlockState(pos);
            if (y == -61) {
                if (!old.isAir() && !old.is(Blocks.GRASS_BLOCK) && !old.is(Blocks.DIRT) && !old.is(Blocks.SMOOTH_STONE))
                    throw new IllegalStateException("Fixture refuses an unexpected floor at " + pos.toShortString());
            } else if (!old.isAir()) {
                throw new IllegalStateException("Fixture refuses occupied clearance at " + pos.toShortString());
            }
            if (targets.containsKey(pos)) before.put(pos, old);
        }
        // bindNearby searches this bounded radius. Reject old members rather than adopting one.
        int range = CinemaTerminalTile.BIND_RANGE;
        for (int x = (TERMINAL.getX() - range) >> 4; x <= (TERMINAL.getX() + range) >> 4; x++)
            for (int z = (TERMINAL.getZ() - range) >> 4; z <= (TERMINAL.getZ() + range) >> 4; z++) {
                if (!level.getChunkSource().hasChunk(x, z)) continue;
                for (BlockEntity tile : level.getChunk(x, z).getBlockEntities().values())
                    if (tile instanceof CinemaProjectorTile && TERMINAL.distSqr(tile.getBlockPos()) <= range * range)
                        throw new IllegalStateException("Fixture refuses an existing projector in the binding radius at " + tile.getBlockPos().toShortString());
            }
        if (before.size() != targets.size()) throw new IllegalStateException("Fixture destinations escaped the reviewed bounds");
        return before;
    }

    private static void setup(ServerPlayer actor) {
        ServerLevel level = world(actor);
        String source = media(actor);
        Map<BlockPos, BlockState> targets = targets(), before = preflight(level, actor, targets);
        Map<BlockPos, BlockEntity> created = new HashMap<>();
        List<BlockPos> written = new ArrayList<>();
        Vec3 originalPosition = actor.position();
        ManagerTile manager = null;
        try {
            for (var entry : targets.entrySet()) {
                BlockPos pos = entry.getKey();
                accessible(level, actor, pos);
                if (!level.getBlockState(pos).equals(before.get(pos)) || level.getBlockEntity(pos) != null)
                    throw new IllegalStateException("Fixture target changed before construction");
                if (entry.getValue().equals(before.get(pos))) continue;
                written.add(pos);
                try { level.setBlock(pos, entry.getValue(), FLAGS); }
                finally { if (level.hasChunkAt(pos)) created.put(pos, level.getBlockEntity(pos)); }
                if (!level.getBlockState(pos).equals(entry.getValue())) throw new IllegalStateException("Fixture placement failed");
            }
            if (!(level.getBlockEntity(MANAGER) instanceof ManagerTile candidate)
                    || !(level.getBlockEntity(TERMINAL) instanceof CinemaTerminalTile terminal))
                throw new IllegalStateException("Fixture devices failed to initialize");
            manager = candidate;
            manager.initializePlacement(actor.getUUID());
            manager.state.name = NAME;
            manager.dirty();
            for (BlockPos pos : PROJECTORS) {
                if (!(level.getBlockEntity(pos) instanceof CinemaProjectorTile projector))
                    throw new IllegalStateException("Fixture projector failed to initialize");
                projector.data.setWidth(8);
                projector.data.setHeight(4.5f);
                projector.data.projectionDistance = 6;
                projector.projectionOffsetY = 2;
                projector.setDirty();
            }
            // Temporary server position only: no teleport, tickets or movement of the real camera.
            actor.setPos(TERMINAL_STAND);
            if (terminal.bindNearby(actor) != 2 || !reciprocal(level, terminal))
                throw new IllegalStateException("Ordinary binding did not produce exactly the reviewed pair");
            actor.setPos(MANAGER_STAND);
            ManagerState.Hall hall = manager.enroll(actor, terminal, false);
            hall.name = "Ownerless S7 QA hall";
            // Existing public media path configures a paused source; it fabricates no duration/evidence.
            manager.media(actor, hall, source, "Synthetic local QA source");
            if (!actor.getUUID().equals(manager.owner) || terminal.theaterOwner() != null
                    || PROJECTORS.stream().anyMatch(p -> ((CinemaProjectorTile) level.getBlockEntity(p)).theaterOwner() != null)
                    || !terminal.data.active || !terminal.data.paused || terminal.data.getSource() != 0
                    || !source.equals(MediaSource.normalize(terminal.data.getUrl()))
                    || terminal.automationGrant() != null || terminal.libraryManaged()
                    || terminal.sourceEvidenceId() != null || terminal.sourceMetadataReady()
                    || manager.state.schedulerEnabled || manager.playback.present(hall.id)
                    || consentCount(manager) != 0 || !manager.library.catalog().films().isEmpty()
                    || !manager.claimReview(actor, hall.id).available())
                throw new IllegalStateException("Fixture did not retain the exact ownerless pre-claim state");
            manager.dirty();
            for (BlockPos pos : written) {
                level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
                level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), Block.UPDATE_CLIENTS);
            }
        } catch (RuntimeException | Error failure) {
            int unrestored = 0;
            if (manager != null) {
                try { EnrollmentRegistry.get(level).releaseAll(manager.identity); }
                catch (RuntimeException | Error rollbackFailure) { unrestored++; }
            }
            Collections.reverse(written);
            for (BlockPos pos : written) {
                try {
                    if (!level.hasChunkAt(pos)) { unrestored++; continue; }
                    if (level.getBlockState(pos).equals(before.get(pos)) && level.getBlockEntity(pos) == null) continue;
                    if (!level.getBlockState(pos).equals(targets.get(pos)) || level.getBlockEntity(pos) != created.get(pos)) {
                        unrestored++;
                        continue;
                    }
                    level.setBlock(pos, before.get(pos), FLAGS);
                    if (!level.getBlockState(pos).equals(before.get(pos)) || level.getBlockEntity(pos) != null) unrestored++;
                } catch (RuntimeException | Error rollbackFailure) { unrestored++; }
            }
            // Do not echo exception text: configured media paths must never enter QA chat/log output.
            throw new IllegalStateException("Hall-claim QA setup failed (" + failure.getClass().getSimpleName()
                    + "); own writes rolled back" + (unrestored == 0 ? "." : "; " + unrestored + " targets need inspection before retrying."));
        } finally {
            actor.setPos(originalPosition);
        }
        actor.sendSystemMessage(Component.literal("Hall-claim QA ready. Manager (64,-60,28); ownerless TMS (64,-60,48); ownerless reciprocal projectors (62,-60,52) and (66,-60,52). Source is configured, active and paused. No grant, consent, film or metadata was synthesized. Use /mycinemahallclaimqa open, then the Film Library's real first-claim review. Setup restored your original position."));
    }

    private static boolean reciprocal(ServerLevel level, CinemaTerminalTile terminal) {
        if (!terminal.projectors().keySet().equals(new HashSet<>(PROJECTORS))) return false;
        for (BlockPos pos : PROJECTORS)
            if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof CinemaProjectorTile projector)
                    || !terminal.contains(projector) || !projector.linkedTo(terminal) || projector.terminal() != terminal) return false;
        return true;
    }

    private static int consentCount(ManagerTile manager) {
        CompoundTag saved = new CompoundTag();
        manager.playback.save(saved);
        return LocalProgrammeConsents.load(CatalogStorage.read(saved, "local_programme_consents")).size();
    }

    private static ManagerTile manager(ServerPlayer actor) {
        ServerLevel level = world(actor);
        accessible(level, actor, MANAGER);
        if (!(level.getBlockEntity(MANAGER) instanceof ManagerTile manager) || !NAME.equals(manager.state.name)
                || !actor.getUUID().equals(manager.owner) || manager.state.halls.size() != 1
                || manager.state.halls.getFirst().terminalPos != TERMINAL.asLong())
            throw new IllegalStateException("The owned hall-claim QA manager is unavailable; setup requires a fresh empty region");
        return manager;
    }

    /** Explicit opt-in teleport to the local manager, over 16 blocks from the TMS. */
    private static void open(ServerPlayer actor) {
        ManagerTile manager = manager(actor);
        BlockPos standing = BlockPos.containing(MANAGER_STAND);
        for (BlockPos pos : List.of(standing.below(), standing, standing.above())) accessible(actor.serverLevel(), actor, pos);
        if (!actor.serverLevel().getBlockState(standing.below()).is(Blocks.SMOOTH_STONE)
                || !actor.serverLevel().getBlockState(standing).isAir()
                || !actor.serverLevel().getBlockState(standing.above()).isAir())
            throw new IllegalStateException("QA manager standing position changed; approach it manually");
        actor.teleportTo(MANAGER_STAND.x, MANAGER_STAND.y, MANAGER_STAND.z);
        actor.setYRot(0);
        actor.setXRot(10);
        ManagerNetwork.open(actor, manager, false);
        inspect(actor);
    }

    private static String owner(UUID owner, ServerPlayer actor) {
        return owner == null ? "null" : owner.equals(actor.getUUID()) ? "actor" : "other";
    }

    /** Read-only diagnostics, including after UI cancel/claim/grant and save/reopen. Never prints media. */
    private static void inspect(ServerPlayer actor) {
        ManagerTile manager = manager(actor);
        var hall = manager.state.halls.getFirst();
        CinemaTerminalTile terminal = manager.programmeTerminal(hall.id);
        boolean pair = reciprocal(actor.serverLevel(), terminal);
        String members = pair ? PROJECTORS.stream()
                .map(p -> owner(((CinemaProjectorTile) actor.serverLevel().getBlockEntity(p)).theaterOwner(), actor))
                .reduce((a, b) -> a + "," + b).orElse("missing") : "changed/unloaded";
        var grant = terminal.automationGrant();
        String grantStatus = grant == null ? "none" : grant.matches(terminal.identity(), manager.identity, manager.owner, MANAGER) ? "matching" : "other";
        actor.sendSystemMessage(Component.literal("Hall-claim QA: managerOwner=" + owner(manager.owner, actor)
                + "; tmsOwner=" + owner(terminal.theaterOwner(), actor) + "; projectorOwners=" + members
                + "; reciprocalPair=" + pair + "; sourcePresent=" + terminal.data.hasUrl()
                + "; sourceIndex=" + terminal.data.getSource() + "; active=" + terminal.data.active + "; paused=" + terminal.data.paused
                + "; automation=" + grantStatus + "; scheduler=" + manager.state.schedulerEnabled
                + "; programme=" + manager.playback.present(hall.id) + "; localConsents=" + consentCount(manager)
                + "; ownerEvidence=" + (terminal.sourceEvidenceId() != null) + "; metadataReady=" + terminal.sourceMetadataReady()
                + "; claim=" + manager.claimReview(actor, hall.id).status()
                + "; programmeStatus=" + HallAuthorization.programmeStatus(manager, hall.id)
                + "; managerRevision=" + manager.state.revision + "."));
    }
}
