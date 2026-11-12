package local.cinemamanager.seats.fractional;

import local.cinemamanager.CinemaManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Layout-scoped bridge for authored map seats (legacy372 and original368). This is not a SeatRegistry/controller migration. */
@EventBusSubscriber(modid = CinemaManager.ID)
public final class FractionalSeatIntegration {
    private static final String SESSION = "mycinema_fractional_map_session";
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, UUID> ALLOWED_MOUNTS = new HashMap<>();
    private static final Set<UUID> RELEASING = new HashSet<>();
    private enum ReleaseResult { RELEASED, BLOCKED_SEATED, DETACHED_UNSAFE }
    private static final class Session {
        final FractionalSeatLayout.Seat seat;
        final UUID marker;
        final String dimension;
        final FractionalSeatLayoutVersion.Version layoutVersion;
        boolean requestedExit;
        int nextNotice, nextRecovery;
        boolean unsafeBlocked;
        String unsafeReason = "";
        Session(FractionalSeatLayout.Seat seat, ArmorStand marker, ServerPlayer rider, FractionalSeatLayoutVersion.Version verifiedVersion) {
            this.layoutVersion = verifiedVersion;
            this.seat = seat; this.marker = marker.getUUID(); this.dimension = rider.level().dimension().location().toString();
        }
    }
    record Entities(ArmorStand marker, Interaction click, Display.ItemDisplay visual) {}

    /** The block's server callback rechecks current reach, exact voxels, owner UID and intervening blocks. */
    public static InteractionResult use(ServerPlayer player, BlockHitResult suppliedHit) {
        if (player.isSpectator() || !player.isAlive() || player.isPassenger()) return InteractionResult.FAIL;
        Optional<RaySeat> target = aimedSeat(player);
        if (target.isEmpty() || !target.get().hit().getBlockPos().equals(suppliedHit.getBlockPos())) return InteractionResult.FAIL;
        return sit(player, target.get().seat());
    }
    private record RaySeat(FractionalSeatLayout.Seat seat, BlockHitResult hit) {}
    private static Optional<RaySeat> aimedSeat(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!level.dimension().equals(Level.OVERWORLD)) return Optional.empty();
        Vec3 start = player.getEyePosition(), direction = player.getViewVector(1);
        double reach = Math.min(8, player.blockInteractionRange());
        Vec3 end = start.add(direction.scale(reach));
        BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || !(level.getBlockEntity(hit.getBlockPos()) instanceof FractionalSeatTile tile)
            || !tile.hasValidData()) return Optional.empty();
        // End just inside the first actual block collision. A retained slab cannot be clicked through.
        Optional<UUID> owner = tile.firstOwnerOnRay(start, hit.getLocation().add(direction.scale(1e-5)));
        if (owner.isEmpty()) return Optional.empty();
        FractionalSeatLayout.Seat seat;
        try { seat = FractionalSeatLayout.get(level).seats.get(owner.get()); }
        catch (RuntimeException invalidContract) { return Optional.empty(); }
        return seat != null && FractionalSeatInstallation.installed(level, seat) ? Optional.of(new RaySeat(seat, hit)) : Optional.empty();
    }
    private static InteractionResult sit(ServerPlayer player, FractionalSeatLayout.Seat seat) {
        if (player.isSpectator() || !player.isAlive() || player.isPassenger()) return InteractionResult.FAIL;
        ServerLevel level = player.serverLevel();
        if (!FractionalSeatInstallation.installed(level, seat)) return InteractionResult.FAIL;
        Entities entities;
        try { var layout=FractionalSeatLayout.get(level); if(layout.isCarpetV2())layout.verifyOriginal368Support(level); entities = entities(level, seat); if (layout.isOriginal368()) FractionalSeatVisuals.verify(level,seat,true); }
        catch (RuntimeException bad) { notice(player, "Seat identity or exact floor contract mismatch; entry refused."); return InteractionResult.FAIL; }
        ArmorStand marker = entities.marker();
        if (!marker.getPassengers().isEmpty()) { notice(player, "This seat is occupied."); return InteractionResult.FAIL; }
        var assessment = FractionalSeatExitSafety.assessEntry(level, player, marker, seat);
        if (!assessment.allowed()) {
            FractionalSeatDiagnostics.entryFailure(player, seat, assessment); return InteractionResult.FAIL;
        }
        var verifiedVersion=FractionalSeatLayout.get(level).version;
        ALLOWED_MOUNTS.put(player.getUUID(), marker.getUUID());
        boolean mounted;
        try { mounted = player.startRiding(marker, true); }
        finally { ALLOWED_MOUNTS.remove(player.getUUID()); }
        if (!mounted) return InteractionResult.FAIL;
        // Mount event callbacks may change tags, the world marker or the actual vehicle.
        boolean stillVerified;
        try{stillVerified=FractionalSeatLayout.get(level).version==verifiedVersion&&player.getVehicle()==marker&&sessionIdentityIntact(level,seat);}
        catch(RuntimeException changedContract){stillVerified=false;}
        if(!stillVerified){clearLegacy(player);notice(player,"Seat contract changed during mounting. Automatic movement refused; operator recovery is required.");return InteractionResult.FAIL;}
        Session session = new Session(seat, marker, player, verifiedVersion); SESSIONS.put(player.getUUID(), session);
        CompoundTag saved = new CompoundTag(); saved.putUUID("seat_uid", seat.uid()); saved.putUUID("marker", marker.getUUID());
        saved.putString("layout", session.layoutVersion.id);
        saved.putString("dimension", session.dimension); saved.putString("model", FractionalSeatLayout.MODEL);
        player.getPersistentData().put(SESSION, saved);
        clearLegacy(player);
        marker.positionRider(player); // Public vanilla API, also used by the baseline CinemaSeatTile.
        var mountedCheck = FractionalSeatExitSafety.assessMountedBody(level, player, seat);
        if (!mountedCheck.allowed()) {
            FractionalSeatDiagnostics.mountedFailure(player, seat, mountedCheck);
            ReleaseResult result = release(player, session);
            if (result == ReleaseResult.BLOCKED_SEATED) {
                unsafeState(player, session, mountedCheck.code());
                notice(player, "Seat UNSAFE/BLOCKED (" + mountedCheck.code() + "). No safe exit is available. Clear the footwell or request operator recovery; no unsafe teleport was made.");
            }
            else if (result == ReleaseResult.RELEASED) notice(player, "Passenger pose validation failed (" + mountedCheck.code() + "). You were returned to the verified exit.");
            return InteractionResult.FAIL;
        }
        // All identity, entry, mounting and actual-body checks have succeeded. Align once with
        // the immutable authored chair front, not with the direction from which it was clicked.
        applyMountFacing(player, seat.anchor().turns());
        // Vanilla's absolute position packet also synchronizes the local client's previous yaw.
        // Coordinates and pitch are unchanged; subsequent mouse/head movement remains unrestricted.
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        return InteractionResult.SUCCESS;
    }
    static void applyMountFacing(ServerPlayer player, int turns) {
        var rotation = FractionalSeatFacing.afterSuccessfulMount(true, turns,
            new FractionalSeatFacing.Rotation(player.getYRot(), player.yRotO, player.getYHeadRot(), player.yHeadRotO,
                player.yBodyRot, player.yBodyRotO, player.getXRot()));
        player.setYRot(rotation.yaw()); player.yRotO = rotation.previousYaw();
        player.setYHeadRot(rotation.headYaw()); player.yHeadRotO = rotation.previousHeadYaw();
        player.setYBodyRot(rotation.bodyYaw()); player.yBodyRotO = rotation.previousBodyYaw();
    }

    /** Prevent the large interaction entity from granting a ride for a ray that misses actual chair solids. */
    @SubscribeEvent public static void interaction(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var result = intercept(player, event.getTarget(), event.getHand());
        if (result.isPresent()) { event.setCanceled(true); event.setCancellationResult(result.get()); }
    }
    @SubscribeEvent public static void interactionAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        var result = intercept(player, event.getTarget(), event.getHand());
        if (result.isPresent()) { event.setCanceled(true); event.setCancellationResult(result.get()); }
    }
    private static Optional<InteractionResult> intercept(ServerPlayer player, Entity target, InteractionHand hand) {
        FractionalSeatLayout.Seat seat;
        try { seat = protectedSeat(player.serverLevel(), target); }
        catch (RuntimeException invalidContract) {
            return authoredTarget(target) ? Optional.of(InteractionResult.FAIL) : Optional.empty();
        }
        if (seat == null) return target.getTags().stream().anyMatch(t -> t.startsWith("o368_"))
            || legacyAuditoriumTarget(target) && FractionalSeatLayout.get(player.serverLevel()).isOriginal368()
            ? Optional.of(InteractionResult.FAIL) : Optional.empty();
        if (!(target instanceof Interaction || target instanceof ArmorStand)) return Optional.empty();
        if (hand != InteractionHand.MAIN_HAND) return Optional.of(InteractionResult.FAIL);
        Optional<RaySeat> aimed = aimedSeat(player);
        return Optional.of(aimed.isPresent() && aimed.get().seat().uid().equals(seat.uid())
            ? sit(player, seat) : InteractionResult.FAIL);
    }
    @SubscribeEvent public static void mount(EntityMountEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntityBeingMounted() instanceof ArmorStand marker)) return;
        if (event.isMounting() && authoredTarget(marker)) {
            try { FractionalSeatLayout.get(level); }
            catch (RuntimeException invalidContract) { event.setCanceled(true); return; }
        }
        if (event.isMounting() && legacyAuditoriumTarget(marker) && FractionalSeatLayout.get(level).isOriginal368()) { event.setCanceled(true); return; }
        if (event.isMounting() && marker.getTags().stream().anyMatch(t -> t.startsWith("o368_")) && protectedSeat(level, marker) == null) { event.setCanceled(true); return; }
        if (!(event.getEntityMounting() instanceof ServerPlayer player)) {
            if (event.isMounting() && protectedSeat(level, marker) != null) event.setCanceled(true);
            return;
        }
        if (RELEASING.contains(player.getUUID())) return;
        Session session = SESSIONS.get(player.getUUID());
        if (event.isMounting()) {
            FractionalSeatLayout.Seat seat = protectedSeat(level, marker);
            if (seat != null && (!marker.getUUID().equals(ALLOWED_MOUNTS.get(player.getUUID())) || !marker.getPassengers().isEmpty()))
                event.setCanceled(true);
        } else if (session != null && session.marker.equals(marker.getUUID()) && player.isAlive() && !player.isRemoved()) {
            // Let the original stack return, then complete the vanilla dismount and validated teleport together.
            event.setCanceled(true); session.requestedExit = true;
            player.connection.send(new ClientboundSetPassengersPacket(marker));
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            Session session = SESSIONS.get(player.getUUID());
            if (session == null) continue;
            clearLegacy(player);
            if (!player.isAlive() || !session.dimension.equals(player.level().dimension().location().toString())) { forget(player); continue; }
            if (!currentLayout(player.serverLevel(), session)) {
                if (event.getServer().getTickCount() >= session.nextNotice) {
                    notice(player, "Seat layout contract changed or is invalid. Automatic movement refused; operator recovery is required.");
                    session.nextNotice = event.getServer().getTickCount() + 100;
                }
                continue;
            }
            if (!sessionIdentityIntact(player.serverLevel(),session.seat)) {
                unsafeState(player,session,"ENTITY_IDENTITY_CHANGED");
                if(event.getServer().getTickCount()>=session.nextNotice){
                    notice(player,"Seat entity identity changed or is invalid. Automatic movement refused; operator recovery is required.");
                    session.nextNotice=event.getServer().getTickCount()+100;
                }
                continue;
            }
            Entity vehicle = player.getVehicle();
            boolean matchingVehicle = vehicle instanceof ArmorStand && session.marker.equals(vehicle.getUUID())
                && sessionMarkerMatches(session.layoutVersion,vehicle.getUUID(),vehicle.getTags(),session.seat);
            if (!matchingVehicle) {
                // A forced removal is handled once. Never repeatedly pull a player back after they can walk away.
                recoverOnce(player, session.seat); SESSIONS.remove(player.getUUID()); continue;
            }
            boolean intact = FractionalSeatInstallation.installed(player.serverLevel(), session.seat)
                && vehicle.position().distanceToSqr(session.seat.marker()) < 1e-8;
            // Every server tick revalidates actual pose, complete support coverage and every other
            // owner against the full body. RLE queries avoid per-tick 64-cubed shape rebuilding.
            var live = FractionalSeatExitSafety.assessMountedBody(player.serverLevel(), player, session.seat);
            boolean unsafe = !intact || !live.allowed();
            String reason = !intact ? "INSTALLED_FOOTPRINT_CHANGED" : live.code();
            boolean newlyUnsafe = unsafe && (!session.unsafeBlocked || !session.unsafeReason.equals(reason));
            if (unsafe) unsafeState(player, session, reason);
            else if (session.unsafeBlocked) unsafeState(player, session, "");
            if (unsafe && !live.allowed()) FractionalSeatDiagnostics.mountedFailure(player, session.seat, live);
            int now = event.getServer().getTickCount();
            if ((session.requestedExit || unsafe) && (session.requestedExit || newlyUnsafe || now >= session.nextRecovery)) {
                session.requestedExit = false; session.nextRecovery = now + 20;
                ReleaseResult result = release(player, session);
                if (result == ReleaseResult.BLOCKED_SEATED) {
                    if (unsafe) unsafeState(player, session, reason);
                    if (now >= session.nextNotice) {
                        notice(player, unsafe
                            ? "Seat UNSAFE/BLOCKED (" + reason + "). No safe exit is available. Clear the footwell or request operator recovery; no unsafe teleport was made."
                            : "Exit is blocked or unloaded. You remain seated; clear the planned footwell and press Shift again.");
                        session.nextNotice = now + 100;
                    }
                }
            }
        }
    }
    private static void unsafeState(ServerPlayer player, Session session, String reason) {
        boolean unsafe = !reason.isEmpty();
        if (session.unsafeBlocked == unsafe && session.unsafeReason.equals(reason)) return;
        session.unsafeBlocked = unsafe; session.unsafeReason = reason;
        if (player.getPersistentData().contains(SESSION)) {
            CompoundTag saved = player.getPersistentData().getCompound(SESSION);
            saved.putBoolean("unsafe_blocked", unsafe);
            if (unsafe) saved.putString("unsafe_reason", reason); else saved.remove("unsafe_reason");
        }
    }
    private static ReleaseResult release(ServerPlayer player, Session session) {
        if (!currentLayout(player.serverLevel(), session)||!sessionIdentityIntact(player.serverLevel(),session.seat)) return ReleaseResult.BLOCKED_SEATED;
        Entity vehicle=player.getVehicle();
        if(!(vehicle instanceof ArmorStand)||!session.marker.equals(vehicle.getUUID())
            ||!sessionMarkerMatches(session.layoutVersion,vehicle.getUUID(),vehicle.getTags(),session.seat))return ReleaseResult.BLOCKED_SEATED;
        Optional<Vec3> exit = FractionalSeatExitSafety.exit(player.serverLevel(), player, session.seat);
        if (exit.isEmpty()) return ReleaseResult.BLOCKED_SEATED;
        RELEASING.add(player.getUUID());
        try { player.stopRiding(); } finally { RELEASING.remove(player.getUUID()); }
        if (player.isPassenger()) return ReleaseResult.BLOCKED_SEATED;
        // Recheck after dismount event callbacks, which may have changed blocks or moved another entity.
        if (currentLayout(player.serverLevel(),session)&&sessionIdentityIntact(player.serverLevel(),session.seat)
            && FractionalSeatExitSafety.safeStanding(player.serverLevel(), player, exit.get())) {
            Vec3 p = exit.get(); player.teleportTo(p.x, p.y, p.z); forget(player); return ReleaseResult.RELEASED;
        }
        notice(player, "Exit changed during dismount. You are no longer seated; unsafe teleport refused. Saved recovery remains for your next login.");
        // Retain persistent recovery, but stop automatic tick retries. Login attempts it once only,
        // in the original dimension and within four blocks; walking away abandons that recovery.
        SESSIONS.remove(player.getUUID()); return ReleaseResult.DETACHED_UNSAFE;
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Session session = SESSIONS.get(player.getUUID());
        if (session != null) { release(player, session); SESSIONS.remove(player.getUUID()); clearLegacy(player); }
        FractionalSeatDiagnostics.forget(player.getUUID());
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.getPersistentData().contains(SESSION)) return;
        CompoundTag saved = player.getPersistentData().getCompound(SESSION);
        if (!saved.hasUUID("seat_uid") || !FractionalSeatLayout.MODEL.equals(saved.getString("model"))
            || !player.level().dimension().location().toString().equals(saved.getString("dimension"))) { forget(player); return; }
        FractionalSeatLayout layout;
        try { layout = FractionalSeatLayout.get(player.serverLevel()); }
        catch (RuntimeException invalidContract) { clearLegacy(player); notice(player, "Invalid seat layout contract; saved recovery refused."); return; }
        if (!savedLayoutMatches(saved, layout.version)) { clearLegacy(player); notice(player, "Saved seat layout differs from this world; recovery refused."); return; }
        var seat = layout.seats.get(saved.getUUID("seat_uid"));
        if (seat == null) { forget(player); return; }
        if(!sessionIdentityIntact(player.serverLevel(),seat)){clearLegacy(player);notice(player,"Saved seat entity identity differs from this world; recovery refused.");return;}
        if (player.isPassenger()) {
            if (player.getVehicle() instanceof ArmorStand marker && saved.hasUUID("marker")
                && marker.getUUID().equals(saved.getUUID("marker")) && sessionMarkerMatches(layout.version,marker.getUUID(),marker.getTags(),seat)
                && marker.position().distanceToSqr(seat.marker()) < 1e-8
                && FractionalSeatInstallation.installed(player.serverLevel(), seat)) {
                Session session = new Session(seat, marker, player, layout.version); session.requestedExit = true;
                session.unsafeBlocked = saved.getBoolean("unsafe_blocked"); session.unsafeReason = saved.getString("unsafe_reason");
                SESSIONS.put(player.getUUID(), session); clearLegacy(player);
            } else forget(player);
            return;
        }
        recoverOnce(player, seat);
    }
    private static void recoverOnce(ServerPlayer player, FractionalSeatLayout.Seat seat) {
        if(!sessionIdentityIntact(player.serverLevel(),seat)){clearLegacy(player);notice(player,"Seat entity identity differs from this world; automatic recovery refused.");return;}
        Vec3 anchor = new Vec3(seat.anchor().x(), seat.anchor().y(), seat.anchor().z());
        if (player.position().distanceToSqr(anchor) > 16 || player.isPassenger()) { forget(player); return; }
        Optional<Vec3> exit = FractionalSeatExitSafety.exit(player.serverLevel(), player, seat);
        if (exit.isPresent()) {
            Vec3 p = exit.get(); player.teleportTo(p.x, p.y, p.z); forget(player);
        } else {
            clearLegacy(player); notice(player, "Your saved seat exit is blocked or unloaded. No unsafe recovery teleport was made.");
        }
    }
    private static void forget(ServerPlayer player) { SESSIONS.remove(player.getUUID()); player.getPersistentData().remove(SESSION); clearLegacy(player); }
    private static void clearLegacy(ServerPlayer player) {
        // Only our rider is isolated from the old block-tag exit dispatcher; all map functions stay intact.
        player.removeTag("os_seated"); player.removeTag("os_mounted_now");
        var board = player.getScoreboard();
        for (String name : new String[]{"os_last", "os_mount"}) {
            var objective = board.getObjective(name);
            if (objective != null) board.getOrCreatePlayerScore(player, objective).set(0);
        }
    }
    private static FractionalSeatLayout.Seat protectedSeat(ServerLevel level, Entity entity) {
        if (!level.dimension().equals(Level.OVERWORLD) || !authoredTarget(entity)) return null;
        FractionalSeatLayout layout = FractionalSeatLayout.get(level);
        if (layout.isOriginal368()) {
            UUID uid = Original368Identity.taggedUid(entity,layout.version).orElse(null);
            var seat = layout.seats.get(uid);
            return seat != null && (Original368Identity.matches(entity,seat,"mount",layout.version)
                || Original368Identity.matches(entity,seat,"click",layout.version)) ? seat : null;
        }
        if (entity.getTags().stream().anyMatch(t -> t.startsWith("o368_"))) return null;
        for (String tag : entity.getTags()) if (tag.startsWith("os_")) {
            var seat = layout.labels.get(tag.substring(3));
            if (seat != null) {
                // The transport block itself is protection evidence even if its tile/payload is missing
                // or malformed. Corruption must never re-enable the coarse legacy interaction path.
                for (var pos : seat.footprint().cells().keySet())
                    if (level.hasChunkAt(pos) && level.getBlockState(pos).is(FractionalSeatModule.CELL.get())) return seat;
                if (FractionalSeatVisuals.hasProtectionEvidence(level, seat)) return seat;
            }
        }
        return null;
    }
    private static boolean legacyAuditoriumTarget(Entity entity) {
        return entity.level().dimension().equals(Level.OVERWORLD) && legacyAuditoriumTarget(entity.getTags(),entity.getX(),entity.getY(),entity.getZ());
    }
    static boolean legacyAuditoriumTarget(Set<String> tags,double x,double y,double z) {
        return x >= 12 && x <= 56 && y >= 68 && y <= 88 && z >= 19 && z <= 59
            && (tags.contains("os_mount") || tags.contains("os_click") || tags.contains("os_visual"));
    }
    private static boolean authoredTarget(Entity entity) {
        return entity.getTags().stream().anyMatch(t -> t.startsWith("o368_")) || legacyAuditoriumTarget(entity) || (entity.getTags().contains("os_mount") || entity.getTags().contains("os_click"))
            && entity.getTags().stream().anyMatch(t -> t.matches("os_[A-L][0-9]{2}"));
    }
    static boolean sessionMarkerMatches(FractionalSeatLayoutVersion.Version version,UUID marker,Set<String> tags,FractionalSeatLayout.Seat seat) {
        return !FractionalSeatLayout.original368Version(version)||Original368Identity.matches(marker,tags,seat,"mount",version);
    }
    private static boolean sessionIdentityIntact(ServerLevel level,FractionalSeatLayout.Seat seat) {
        try {
            var layout=FractionalSeatLayout.get(level);
            if(layout.seats.get(seat.uid())!=seat)return false;
            if(!layout.isOriginal368())return true;
            if(!level.dimension().equals(Level.OVERWORLD))return false;
            Entity marker=level.getEntity(Original368Identity.entityUid(seat.uid(),"mount"));
            if(!(marker instanceof ArmorStand)||!sessionMarkerMatches(layout.version,marker.getUUID(),marker.getTags(),seat))return false;
            // This also resolves and verifies all three exact role identities, positions and transforms.
            FractionalSeatVisuals.verify(level,seat,true);
            return true;
        }catch(RuntimeException invalidIdentity){return false;}
    }
    private static boolean currentLayout(ServerLevel level, Session session) {
        try { return FractionalSeatLayout.get(level).version == session.layoutVersion; }
        catch (RuntimeException invalidContract) { return false; }
    }
    static boolean savedLayoutMatches(CompoundTag saved, FractionalSeatLayoutVersion.Version version) {
        if (!saved.contains("layout")) return version == FractionalSeatLayoutVersion.Version.ORIGINAL;
        return saved.contains("layout", 8) && version.id.equals(saved.getString("layout"));
    }
    static void verifyEntities(ServerLevel level, FractionalSeatLayout layout, boolean requireEmpty) {
        if (layout.isOriginal368()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getTags().stream().anyMatch(t -> t.startsWith("o368_"))) {
                    var seat = layout.seats.get(Original368Identity.taggedUid(entity,layout.version).orElse(null));
                    if (seat == null || !(Original368Identity.matches(entity,seat,"mount",layout.version) || Original368Identity.matches(entity,seat,"click",layout.version) || Original368Identity.matches(entity,seat,"visual",layout.version)))
                        throw new IllegalStateException("foreign original368 entity identity " + entity.getUUID());
                }
                if (legacyAuditoriumTarget(entity))
                    throw new IllegalStateException("stale legacy372 entity inside original368 auditorium");
            }
        }
        for (var seat : layout.seats.values()) {
            Entities entities = entities(level, seat);
            if (requireEmpty && !entities.marker().getPassengers().isEmpty()) throw new IllegalStateException("occupied authored marker " + seat.label());
        }
    }
    static Entities entities(ServerLevel level, FractionalSeatLayout.Seat seat) {
        ArmorStand marker = null; Interaction click = null; Display.ItemDisplay visual = null;
        FractionalSeatLayout layout=FractionalSeatLayout.get(level);
        boolean original368 = layout.isOriginal368();
        String tag = original368 ? Original368Identity.uidTag(seat.uid()) : "os_" + seat.label();
        String prefix = original368 ? "o368_" : "os_";
        for (Entity entity : level.getAllEntities()) {
            if (!entity.getTags().contains(tag)) continue;
            if (!original368 && entity.getTags().stream().filter(t -> t.matches("os_[A-L][0-9]{2}")).count() != 1) throw badEntity(seat);
            if (original368 && !(Original368Identity.matches(entity,seat,"mount",layout.version) || Original368Identity.matches(entity,seat,"click",layout.version) || Original368Identity.matches(entity,seat,"visual",layout.version))) throw badEntity(seat);
            if (entity.getTags().contains(prefix + "mount")) {
                if (marker != null || !(entity instanceof ArmorStand a) || !a.isMarker()
                    || a.isRemoved() || !a.isNoGravity() || !a.isInvisible()
                    || a.position().distanceToSqr(seat.marker()) > 1e-8) throw badEntity(seat);
                marker = a;
            }
            if (entity.getTags().contains(prefix + "click")) {
                if (click != null || !(entity instanceof Interaction i) || i.isRemoved()
                    || i.position().distanceToSqr(seat.click()) > 1e-8) throw badEntity(seat);
                click = i;
            }
            if (entity.getTags().contains(prefix + "visual")) {
                if (visual != null || !(entity instanceof Display.ItemDisplay d) || d.isRemoved()
                    || d.position().distanceToSqr(seat.visual()) > 1e-8 || !authoredTransform(d)) throw badEntity(seat);
                visual = d;
            }
        }
        if (marker == null || click == null || visual == null) throw badEntity(seat);
        return new Entities(marker, click, visual);
    }
    private static boolean authoredTransform(Display.ItemDisplay display) {
        CompoundTag saved = new CompoundTag(); display.saveWithoutId(saved);
        if (!"none".equals(saved.getString("item_display")) || !"fixed".equals(saved.getString("billboard"))
            || Math.abs(display.getYRot()) > 1e-5 || Math.abs(display.getXRot()) > 1e-5) return false;
        Tag transform = saved.get("transformation");
        if (transform instanceof CompoundTag parts)
            return vectorEquals(parts.get("scale"), 1, 1, 1) && vectorEquals(parts.get("translation"), 0, 0, 0)
                && vectorEquals(parts.get("left_rotation"), 0, 1, 0, 0) && vectorEquals(parts.get("right_rotation"), 0, 0, 0, 1);
        // Vanilla may serialize the same transformation as its equivalent row-major matrix.
        return vectorEquals(transform, -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1);
    }
    private static boolean vectorEquals(Tag tag, double... expected) {
        if (!(tag instanceof ListTag list) || list.size() != expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (!(list.get(i) instanceof NumericTag number) || !Double.isFinite(number.getAsDouble())
                || Math.abs(number.getAsDouble() - expected[i]) > 1e-5) return false;
        }
        return true;
    }
    private static IllegalStateException badEntity(FractionalSeatLayout.Seat seat) { return new IllegalStateException("missing, duplicate or moved authored entity for " + seat.label()); }
    private static void notice(ServerPlayer player, String text) { player.displayClientMessage(Component.literal(text), true); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { SESSIONS.clear(); ALLOWED_MOUNTS.clear(); RELEASING.clear(); FractionalSeatDiagnostics.clear(); }
    private FractionalSeatIntegration() {}
}
