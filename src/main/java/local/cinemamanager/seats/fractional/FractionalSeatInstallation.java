package local.cinemamanager.seats.fractional;

import local.cinemamanager.CinemaManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import java.util.*;

/** Operator-only reversible installation in the already migrated, exact selected v4 map. */
@EventBusSubscriber(modid = CinemaManager.ID)
public final class FractionalSeatInstallation {
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        for (String branch : List.of("selected150", "original368", "original368_carpet_v2")) {
            event.getDispatcher().register(Commands.literal("fractional_seats").requires(s -> s.hasPermission(2))
                .then(Commands.literal(branch)
                    .then(Commands.literal("verify").executes(c -> dispatch(c.getSource(), "verify", branch)))
                    .then(Commands.literal("diagnose").then(Commands.argument("seat", StringArgumentType.word())
                        .executes(c -> diagnose(c.getSource(), StringArgumentType.getString(c, "seat"), branch))))
                    .then(Commands.literal("install").executes(c -> dispatch(c.getSource(), "install", branch)))
                    .then(Commands.literal("uninstall").executes(c -> dispatch(c.getSource(), "uninstall", branch)))
                    .then(Commands.literal("repair_install").executes(c -> dispatch(c.getSource(), "repair_install", branch)))
                    .then(Commands.literal("repair_uninstall").executes(c -> dispatch(c.getSource(), "repair_uninstall", branch)))));
        }
    }
    static boolean branchMatches(FractionalSeatLayoutVersion.Version version, boolean original368) {
        return branchMatches(version,original368?"original368":"selected150");
    }
    static boolean branchMatches(FractionalSeatLayoutVersion.Version version,String branch) {
        return switch(branch){
            case "original368" -> version==FractionalSeatLayoutVersion.Version.ORIGINAL368_V1;
            case "original368_carpet_v2" -> version==FractionalSeatLayoutVersion.Version.ORIGINAL368_CARPET_V2;
            case "selected150" -> !FractionalSeatLayout.original368Version(version);
            default -> false;
        };
    }
    private static void requireBranch(CommandSourceStack source, String branch) {
        if (!branchMatches(FractionalSeatLayoutVersion.forLevel(source.getLevel()),branch))
            throw new IllegalStateException("command branch differs from exact world layout; no conversion or label matching is permitted");
    }
    private static int diagnose(CommandSourceStack source, String seat, String branch) {
        try { requireBranch(source,branch); return FractionalSeatDiagnostics.diagnose(source,seat); }
        catch (RuntimeException failure) { source.sendFailure(Component.literal("Fractional seats refused: " + failure.getMessage())); return 0; }
    }
    private static int dispatch(CommandSourceStack source, String action, String branch) {
        try {
            requireBranch(source,branch);
            if (action.equals("repair_install") || action.equals("repair_uninstall")) return repair(source, action.equals("repair_install"));
            return run(source,action);
        } catch (RuntimeException failure) { source.sendFailure(Component.literal("Fractional seats refused: " + failure.getMessage())); return 0; }
    }
    private static int run(CommandSourceStack source, String action) {
        try {
            ServerLevel level = source.getLevel(); FractionalSeatLayout layout = FractionalSeatLayout.get(level);
            boolean installed = preflight(level, layout, !action.equals("verify"));
            if (action.equals("verify")) {
                source.sendSuccess(() -> Component.literal(installed
                    ? "Selected 1.50 m collision verified: " + layout.seats.size() + " UIDs, " + layout.cells.size() + " cells; all authored marker/click/visual identities match. Native pose/head clearance is not certified."
                    : "Exact selected map preflight passed: collision is not installed. Native pose/head clearance is not certified."), false);
                return installed ? 2 : 1;
            }
            boolean install = action.equals("install");
            if (installed == install) {
                source.sendSuccess(() -> Component.literal(install ? "Selected collision is already installed." : "Selected collision is already uninstalled."), false);
                return 1;
            }
            List<FractionalSeatLayout.Seat> changed = new ArrayList<>(), visualsChanged = new ArrayList<>();
            try {
                for (var seat : layout.seats.values()) {
                    if (install) FractionalSeatCells.installWithVerifiedSlabHost(level, seat.footprint());
                    else FractionalSeatCells.remove(level, seat.footprint());
                    changed.add(seat);
                }
                for (var seat : layout.seats.values()) {
                    if (install) FractionalSeatVisuals.install(level, seat); else FractionalSeatVisuals.uninstall(level, seat);
                    visualsChanged.add(seat);
                }
                boolean finalInstalled = preflight(level, layout, true);
                if (finalInstalled != install) throw new IllegalStateException("final installation readback disagrees");
            } catch (RuntimeException failure) {
                int refused = 0;
                Collections.reverse(visualsChanged);
                for (var seat : visualsChanged) {
                    try {
                        if (install) FractionalSeatVisuals.uninstall(level, seat); else FractionalSeatVisuals.install(level, seat);
                    } catch (RuntimeException conflict) { refused++; failure.addSuppressed(conflict); }
                }
                Collections.reverse(changed);
                for (var seat : changed) {
                    try {
                        // The cell transaction verifies current owners/hosts. It never overwrites callback changes blindly.
                        if (install) FractionalSeatCells.remove(level, seat.footprint());
                        else FractionalSeatCells.installWithVerifiedSlabHost(level, seat.footprint());
                    } catch (RuntimeException conflict) { refused++; failure.addSuppressed(conflict); }
                }
                throw new IllegalStateException("Operation stopped; " + (refused == 0 ? "completed seat transactions rolled back" : refused + " rollback conflicts preserved for inspection") + ". " + failure.getMessage(), failure);
            }
            source.sendSuccess(() -> Component.literal(install
                ? "Installed selected 1.50 m collision for " + layout.seats.size() + " seats in " + layout.version.id + ". Authored entities/poses retained; native acceptance remains separate."
                : "Uninstalled selected collision and restored exact host blocks. Original368 keeps its selected 1.50 m item; legacy layouts restore their backed-up visual."), true);
            return layout.seats.size();
        } catch (RuntimeException failure) {
            source.sendFailure(Component.literal("Fractional seats refused: " + failure.getMessage())); return 0;
        }
    }
    private static final class RepairChange {
        final FractionalSeatLayout.Seat seat;
        final FractionalSeatFootprint prior;
        final FractionalSeatVisuals.Snapshot visualBefore;
        FractionalSeatVisuals.Snapshot visualAfter;
        RepairChange(FractionalSeatLayout.Seat seat, FractionalSeatFootprint prior, FractionalSeatVisuals.Snapshot visual) {
            this.seat = seat; this.prior = prior; this.visualBefore = visual;
        }
    }
    private static int repair(CommandSourceStack source, boolean install) {
        List<RepairChange> started = new ArrayList<>();
        try {
            ServerLevel level = source.getLevel(); FractionalSeatLayout layout = FractionalSeatLayout.get(level);
            if (!level.dimension().equals(Level.OVERWORLD)) throw new IllegalArgumentException("the selected v4 layout is overworld-only");
            // Verify the complete operation before the first mutation. A mixed layout is accepted only here.
            for (var entry : layout.cells.entrySet()) {
                BlockPos pos = entry.getKey();
                if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) throw at(pos, "unloaded or out-of-bounds cell");
                BlockState expected = expectedHost(level, pos, entry.getValue()), state = level.getBlockState(pos);
                if (state.is(FractionalSeatModule.CELL.get())) {
                    if (!(level.getBlockEntity(pos) instanceof FractionalSeatTile tile) || !tile.hasValidData() || !tile.host().equals(expected)) throw at(pos, "foreign or malformed retained host");
                    for (var owner : tile.data().owners())
                        if (!entry.getValue().owner(owner.uid()).filter(owner::equals).isPresent()) throw at(pos, "foreign owner/model contribution");
                } else if (!state.equals(expected) || level.getBlockEntity(pos) != null) throw at(pos, "foreign original host");
            }
            layout.verifyOriginal368Support(level);
            FractionalSeatIntegration.verifyEntities(level, layout, true);
            List<RepairChange> plan = new ArrayList<>();
            for (var seat : layout.seats.values()) plan.add(new RepairChange(seat, actualFootprint(level, seat), FractionalSeatVisuals.capture(level, seat)));
            if (install) ensureNoNewEntityIntersection(level, layout);
            try {
                for (RepairChange change : plan) {
                    started.add(change);
                    if (install) FractionalSeatCells.replaceWithVerifiedSlabHost(level, change.prior, change.seat.footprint());
                    else FractionalSeatCells.remove(level, change.seat.footprint()); // Idempotent; also removes empty owned proxy cells.
                    try { FractionalSeatVisuals.reconcile(level, change.seat, install); }
                    finally {
                        try { change.visualAfter = FractionalSeatVisuals.capture(level, change.seat); }
                        catch (RuntimeException foreignVisual) { /* The original failure reports it; preserve foreign state. */ }
                    }
                }
                if (preflight(level, layout, true) != install) throw new IllegalStateException("repair final readback disagrees");
            } catch (RuntimeException failure) {
                int conflicts = 0;
                Collections.reverse(started);
                for (RepairChange change : started) {
                    try {
                        // Restore exactly the previous partial UID footprint, never a whole-seat guess.
                        if (change.visualAfter != null) FractionalSeatVisuals.restore(level, change.seat, change.visualBefore, change.visualAfter);
                        else if (!FractionalSeatVisuals.same(FractionalSeatVisuals.capture(level, change.seat), change.visualBefore))
                            throw new IllegalStateException("visual rollback state cannot be verified");
                    } catch (RuntimeException conflict) { conflicts++; failure.addSuppressed(conflict); }
                    try { FractionalSeatCells.replaceWithVerifiedSlabHost(level, actualFootprint(level, change.seat), change.prior); }
                    catch (RuntimeException conflict) { conflicts++; failure.addSuppressed(conflict); }
                }
                throw new IllegalStateException("Repair stopped; prior partial ownership restored where safe; " + conflicts + " conflicts retained. " + failure.getMessage(), failure);
            }
            source.sendSuccess(() -> Component.literal(install
                ? "Repaired the exact selected layout to fully installed collision and 1.50 m visual items."
                : "Repaired the exact selected layout to original host blocks and its layout-specific base visual items."), true);
            return layout.seats.size();
        } catch (RuntimeException failure) { source.sendFailure(Component.literal("Fractional seat repair refused: " + failure.getMessage())); return 0; }
    }
    private static FractionalSeatFootprint actualFootprint(ServerLevel level, FractionalSeatLayout.Seat seat) {
        Map<BlockPos, FractionalSeatData.Contribution> actual = new LinkedHashMap<>();
        for (var entry : seat.footprint().cells().entrySet()) {
            BlockPos pos = entry.getKey();
            if (!level.hasChunkAt(pos)) throw at(pos, "unloaded partial footprint");
            if (level.getBlockEntity(pos) instanceof FractionalSeatTile tile) {
                if (!tile.hasValidData()) throw at(pos, "malformed partial footprint");
                var owner = tile.data().owner(seat.uid());
                if (owner.isPresent()) {
                    if (!owner.get().equals(entry.getValue())) throw at(pos, "foreign partial contribution");
                    actual.put(pos, owner.get());
                }
            }
        }
        return new FractionalSeatFootprint(seat.uid(), actual);
    }
    private static void ensureNoNewEntityIntersection(ServerLevel level, FractionalSeatLayout layout) {
        for (var entry : layout.cells.entrySet()) {
            BlockPos pos = entry.getKey();
            var entities = level.getEntities((Entity)null, new AABB(pos), e -> !e.isSpectator()
                && (e instanceof LivingEntity || e.canBeCollidedWith()) && !(e instanceof ArmorStand a && a.isMarker()));
            if (entities.isEmpty()) continue;
            var shape = FractionalSeatTile.buildShape(entry.getValue(), expectedHost(level, pos, entry.getValue()))
                .move(pos.getX(), pos.getY(), pos.getZ());
            for (Entity entity : entities) if (Shapes.joinIsNotEmpty(Shapes.create(entity.getBoundingBox().deflate(1e-7)), shape, BooleanOp.AND))
                throw at(pos, "an entity intersects the proposed collision; move it clear before installation");
        }
    }
    static boolean preflight(ServerLevel level, FractionalSeatLayout layout, boolean requireEmpty) {
        if (!level.dimension().equals(Level.OVERWORLD)) throw new IllegalArgumentException("the selected v4 layout is overworld-only");
        int installed = 0;
        for (var entry : layout.cells.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) throw at(pos, "unloaded or out-of-bounds cell");
            BlockState expectedHost = expectedHost(level, pos, entry.getValue()); BlockState state = level.getBlockState(pos);
            if (state.is(FractionalSeatModule.CELL.get())) {
                if (!(level.getBlockEntity(pos) instanceof FractionalSeatTile tile) || !tile.hasValidData()
                    || !tile.host().equals(expectedHost) || !same(tile.data(), entry.getValue())) throw at(pos, "installed host/owner/model mismatch");
                installed++;
            } else if (!state.equals(expectedHost) || level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty())
                throw at(pos, "host is not the exact expected dry terrain state for this world layout");
        }
        if (installed != 0 && installed != layout.cells.size()) throw new IllegalStateException("partial layout found; use repair_install or repair_uninstall, which accept only exact known subsets and retained backups");
        if (installed == 0 && requireEmpty) ensureNoNewEntityIntersection(level, layout);
        layout.verifyOriginal368Support(level);
        FractionalSeatIntegration.verifyEntities(level, layout, requireEmpty);
        for (var seat : layout.seats.values()) FractionalSeatVisuals.verify(level, seat, installed != 0);
        return installed != 0;
    }
    private static BlockState expectedHost(ServerLevel level, BlockPos pos, FractionalSeatData data) {
        FractionalSeatLayout layout = FractionalSeatLayout.get(level);
        if (layout.isSmoothed()) return layout.expectedHost(pos);
        boolean slab = data.owners().stream().anyMatch(o -> pos.getY() < o.anchor().y());
        return slab ? FractionalSeatFloorPalette.forLevel(level).slabSupport()
            : Blocks.AIR.defaultBlockState();
    }
    static boolean same(FractionalSeatData a, FractionalSeatData b) {
        if (a.owners().size() != b.owners().size()) return false;
        for (var owner : a.owners()) if (!b.owner(owner.uid()).filter(owner::equals).isPresent()) return false;
        return true;
    }
    static boolean installed(ServerLevel level, FractionalSeatLayout.Seat seat) {
        FractionalSeatLayout layout;
        try { layout = FractionalSeatLayout.get(level); }
        catch (RuntimeException invalidContract) { return false; }
        if (layout.seats.get(seat.uid()) != seat) return false;
        for (var entry : seat.footprint().cells().entrySet()) {
            BlockPos pos = entry.getKey();
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(FractionalSeatModule.CELL.get())
                || !(level.getBlockEntity(pos) instanceof FractionalSeatTile tile) || !tile.hasValidData()
                || !tile.data().owner(seat.uid()).filter(entry.getValue()::equals).isPresent()
                || (layout.isSmoothed() && !tile.host().equals(layout.expectedHost(pos)))) return false;
        }
        return true;
    }
    @SubscribeEvent public static void protectSharedCell(BlockEvent.BreakEvent event) {
        if (event.getState().is(FractionalSeatModule.CELL.get())) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.literal("This cell can belong to several seats. Use the operator-only complete-layout uninstall; individual block breaking is refused."), true);
        }
    }
    private static IllegalStateException at(BlockPos pos, String text) { return new IllegalStateException(text + " at " + pos.toShortString()); }
    private FractionalSeatInstallation() {}
}
