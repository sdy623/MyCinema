package local.cinemamanager.mirror;

import java.util.Objects;
import java.util.UUID;
import local.cinemamanager.policy.CinemaPermissions;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import me.srrapero720.waterframes.common.block.data.DisplayCaps;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Server-authoritative, loaded-only display publication owned by MyCinema. */
public final class CinemaMirrors {
    public static final int RANGE = 8;

    public enum SourceKind { TMS, CINEMA_PROJECTOR, TV, PROJECTOR }

    public record BindingRef(ResourceLocation dimension, BlockPos sourcePos, UUID sourceIdentity,
                             UUID mirrorIdentity, UUID claimant, BlockPos authorityPos,
                             UUID authorityIdentity, UUID authorityMirrorIdentity, UUID authorityOwner,
                             UUID boundBy, GameType grantedMode) {}

    public record Inspection(SourceKind kind, BlockPos sourcePos, UUID sourceIdentity,
                             UUID mirrorIdentity, UUID claimant, BlockPos authorityPos,
                             UUID authorityIdentity, UUID authorityMirrorIdentity, UUID authorityOwner,
                             String status) {}

    public record Result(String error, Inspection inspection, BindingRef binding) {}

    private CinemaMirrors() {}

    private static CinemaMirrorStateAccess state(DisplayTile tile) {
        return tile instanceof CinemaMirrorStateAccess access ? access : null;
    }

    public static UUID mirrorIdentity(DisplayTile tile) {
        CinemaMirrorStateAccess access = state(tile);
        return access == null ? null : access.mycinema$mirrorIdentity();
    }

    public static UUID claimant(DisplayTile tile) {
        if (tile instanceof CinemaDeviceTile device) return device.theaterOwner();
        CinemaMirrorStateAccess access = state(tile);
        return access == null ? null : access.mycinema$mirrorClaimant();
    }

    public static void rotate(DisplayTile tile) {
        CinemaMirrorStateAccess access = state(tile);
        if (access != null) access.mycinema$rotateMirrorIdentity();
    }

    public static void placedBy(DisplayTile tile, ServerPlayer actor) {
        // This path is called only from NeoForge's accepted server-side placement
        // event. Do not require PlayerList lookup here: integrated/FakePlayer block
        // placers are still authenticated event actors and must retain provenance.
        if (!loaded(tile) || actor == null || actor.level() != tile.getLevel() || actor.isSpectator()
                || actor.distanceToSqr(tile.getBlockPos().getCenter()) > RANGE * RANGE
                || !actor.level().mayInteract(actor, tile.getBlockPos()) || tile instanceof CinemaDeviceTile) return;
        CinemaMirrorStateAccess access = state(tile);
        if (access != null) access.mycinema$mirrorClaimant(actor.getUUID(), true);
    }

    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer actor && event.getLevel() instanceof Level level
                && level.getBlockEntity(event.getPos()) instanceof DisplayTile tile) placedBy(tile, actor);
    }

    private static boolean loaded(DisplayTile tile) {
        Level level = tile == null ? null : tile.getLevel();
        return level != null && !tile.isRemoved() && level.hasChunkAt(tile.getBlockPos())
                && level.getBlockEntity(tile.getBlockPos()) == tile
                && (!(level instanceof ServerLevel server) || server.getServer().isSameThread());
    }

    private static SourceKind kind(DisplayTile tile) {
        if (tile instanceof CinemaTerminalTile) return SourceKind.TMS;
        if (tile instanceof CinemaProjectorTile) return SourceKind.CINEMA_PROJECTOR;
        if (tile.caps == DisplayCaps.PROJECTOR) return SourceKind.PROJECTOR;
        if (tile.caps == DisplayCaps.TV || tile.caps == DisplayCaps.BIG_TV || tile.caps == DisplayCaps.TV_BOX)
            return SourceKind.TV;
        return null;
    }

    private static UUID sourceIdentity(DisplayTile tile) {
        if (tile instanceof CinemaTerminalTile terminal) return terminal.identity();
        if (tile instanceof CinemaProjectorTile projector) return projector.identity();
        return mirrorIdentity(tile);
    }

    private static DisplayTile authority(DisplayTile tile) {
        return tile instanceof CinemaProjectorTile projector ? projector.terminal() : tile;
    }

    public static Inspection inspect(DisplayTile source) {
        if (!loaded(source) || kind(source) == null) return null;
        DisplayTile authority = authority(source);
        UUID sourceMirror = mirrorIdentity(source);
        if (sourceMirror == null) return null;
        if (!loaded(authority)) return new Inspection(kind(source), source.getBlockPos().immutable(),
                sourceIdentity(source), sourceMirror, claimant(source),
                authority == null ? source.getBlockPos().immutable() : authority.getBlockPos().immutable(),
                authority == null ? null : sourceIdentity(authority), authority == null ? null : mirrorIdentity(authority),
                authority == null ? null : claimant(authority), "authority_unavailable");
        UUID owner = claimant(authority);
        String status = owner == null ? "unclaimed" : "ready";
        return new Inspection(kind(source), source.getBlockPos().immutable(), sourceIdentity(source), sourceMirror,
                claimant(source), authority.getBlockPos().immutable(), sourceIdentity(authority),
                mirrorIdentity(authority), owner, status);
    }

    private static String actorError(DisplayTile source, ServerPlayer actor) {
        if (!loaded(source) || actor == null || source.getLevel() != actor.level() || actor.isSpectator()
                || actor.server.getPlayerList().getPlayer(actor.getUUID()) != actor
                || !actor.level().mayInteract(actor, source.getBlockPos())) return "invalid_actor_or_source";
        return actor.distanceToSqr(source.getBlockPos().getCenter()) <= RANGE * RANGE ? null : "out_of_range";
    }

    private static boolean actualActor(DisplayTile source, ServerPlayer actor) {
        return actorError(source, actor) == null;
    }

    private static boolean actorOwns(Inspection inspection, ServerPlayer actor) {
        return inspection != null && (actor.getUUID().equals(inspection.claimant())
                && actor.getUUID().equals(inspection.authorityOwner()) || CinemaPermissions.isAdmin(actor));
    }

    private static Result result(String error, DisplayTile source) {
        return new Result(error, inspect(source), null);
    }

    public static Result claim(DisplayTile source, ServerPlayer actor, UUID expected) {
        String actorError = actorError(source, actor);
        if (actorError != null) return result(actorError, source);
        Inspection inspection = inspect(source);
        if (inspection == null) return result("invalid_actor_or_source", source);
        if (!Objects.equals(expected, inspection.mirrorIdentity())) return result("stale_source", source);
        if (source instanceof CinemaDeviceTile) return result("existing_theater_ownership", source);
        CinemaMirrorStateAccess access = state(source);
        if (access == null) return result("invalid_actor_or_source", source);
        UUID current = access.mycinema$mirrorClaimant();
        if (actor.getUUID().equals(current)) return new Result(null, inspection, null);
        if (!CinemaPermissions.isAdmin(actor)) return result("administrator_required", source);
        access.mycinema$mirrorClaimant(actor.getUUID(), true);
        return new Result(null, inspect(source), null);
    }

    public static Result revoke(DisplayTile source, ServerPlayer actor, UUID expected) {
        String actorError = actorError(source, actor);
        if (actorError != null) return result(actorError, source);
        Inspection inspection = inspect(source);
        if (inspection == null) return result("invalid_actor_or_source", source);
        if (!Objects.equals(expected, inspection.mirrorIdentity())) return result("stale_source", source);
        if (!actorOwns(inspection, actor)) return result("owner_required", source);
        CinemaMirrorStateAccess access = state(source);
        if (access == null) return result("invalid_actor_or_source", source);
        if (source instanceof CinemaDeviceTile) access.mycinema$rotateMirrorIdentity();
        else access.mycinema$mirrorClaimant(null, true);
        return new Result(null, inspect(source), null);
    }

    public static Result authorize(DisplayTile source, ServerPlayer actor, UUID expected) {
        String actorError = actorError(source, actor);
        if (actorError != null) return result(actorError, source);
        Inspection inspection = inspect(source);
        if (inspection == null) return result("invalid_actor_or_source", source);
        if (!Objects.equals(expected, inspection.mirrorIdentity())) return result("stale_source", source);
        if (inspection.authorityOwner() == null || inspection.claimant() == null) return result("unclaimed", source);
        if (!actorOwns(inspection, actor)) return result("owner_required", source);
        DisplayTile authority = authority(source);
        GameType mode = actor.gameMode.getGameModeForPlayer();
        if (!CinemaPermissions.canMirrorOffline(actor.server, actor.getUUID(), mode, source.getBlockState().getBlock())
                || authority == null || !CinemaPermissions.canMirrorOffline(actor.server, actor.getUUID(), mode,
                authority.getBlockState().getBlock())) return result("permission", source);
        BindingRef ref = new BindingRef(((ServerLevel) source.getLevel()).dimension().location(),
                inspection.sourcePos(), inspection.sourceIdentity(), inspection.mirrorIdentity(), inspection.claimant(),
                inspection.authorityPos(), inspection.authorityIdentity(), inspection.authorityMirrorIdentity(),
                inspection.authorityOwner(), actor.getUUID(), mode);
        return new Result(null, inspection, ref);
    }

    public static DisplayTile matchLoaded(Level current, BindingRef ref) {
        if (!valid(ref) || current == null) return null;
        Level level = current;
        if (current instanceof ServerLevel server) {
            level = server.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, ref.dimension()));
            if (level == null) return null;
        } else if (!current.dimension().location().equals(ref.dimension())) return null;
        if (!level.hasChunkAt(ref.sourcePos()) || !(level.getBlockEntity(ref.sourcePos()) instanceof DisplayTile source)) return null;
        Inspection now = inspect(source);
        return now != null && ref.sourceIdentity().equals(now.sourceIdentity())
                && ref.mirrorIdentity().equals(now.mirrorIdentity()) && ref.claimant().equals(now.claimant())
                && ref.authorityPos().equals(now.authorityPos()) && ref.authorityIdentity().equals(now.authorityIdentity())
                && ref.authorityMirrorIdentity().equals(now.authorityMirrorIdentity())
                && ref.authorityOwner().equals(now.authorityOwner()) ? source : null;
    }

    public static DisplayTile resolveLoaded(ServerLevel current, BindingRef ref) {
        DisplayTile source = matchLoaded(current, ref);
        if (source == null) return null;
        DisplayTile authority = authority(source);
        if (authority == null || !loaded(authority)) return null;
        Inspection now = inspect(source);
        boolean ownsScope = now != null && ref.boundBy().equals(now.claimant())
                && ref.boundBy().equals(now.authorityOwner());
        if (!ownsScope && !CinemaPermissions.isCurrentMirrorAdministrator(current.getServer(), ref.boundBy()))
            return null;
        if (!CinemaPermissions.canMirrorOffline(current.getServer(), ref.boundBy(), ref.grantedMode(), source.getBlockState().getBlock())
                || !CinemaPermissions.canMirrorOffline(current.getServer(), ref.boundBy(), ref.grantedMode(), authority.getBlockState().getBlock())) return null;
        ServerPlayer online = current.getServer().getPlayerList().getPlayer(ref.boundBy());
        if (online != null && (online.isSpectator() || online.level() != source.getLevel()
                || !online.level().mayInteract(online, source.getBlockPos())
                || !online.level().mayInteract(online, authority.getBlockPos()))) return null;
        return source;
    }

    private static boolean uuid(UUID value) { return value != null && !value.equals(new UUID(0, 0)); }

    public static boolean valid(BindingRef ref) {
        return ref != null && ref.dimension() != null && ref.sourcePos() != null && ref.authorityPos() != null
                && uuid(ref.sourceIdentity()) && uuid(ref.mirrorIdentity()) && uuid(ref.claimant())
                && uuid(ref.authorityIdentity()) && uuid(ref.authorityMirrorIdentity()) && uuid(ref.authorityOwner())
                && uuid(ref.boundBy()) && ref.grantedMode() != null && ref.grantedMode() != GameType.SPECTATOR;
    }

    public static CompoundTag write(BindingRef ref) {
        CompoundTag tag = new CompoundTag();
        if (!valid(ref)) return tag;
        tag.putInt("schema", 1); tag.putString("dimension", ref.dimension().toString());
        tag.putLong("source_pos", ref.sourcePos().asLong()); tag.putLong("authority_pos", ref.authorityPos().asLong());
        tag.putUUID("source_id", ref.sourceIdentity()); tag.putUUID("mirror_id", ref.mirrorIdentity());
        tag.putUUID("claimant", ref.claimant()); tag.putUUID("authority_id", ref.authorityIdentity());
        tag.putUUID("authority_mirror_id", ref.authorityMirrorIdentity()); tag.putUUID("authority_owner", ref.authorityOwner());
        tag.putUUID("bound_by", ref.boundBy()); tag.putInt("mode", ref.grantedMode().getId());
        return tag;
    }

    public static BindingRef read(CompoundTag tag) {
        if (tag == null || tag.getInt("schema") != 1 || !tag.contains("dimension", Tag.TAG_STRING)
                || !tag.contains("source_pos", Tag.TAG_LONG) || !tag.contains("authority_pos", Tag.TAG_LONG)
                || !tag.contains("mode", Tag.TAG_INT) || tag.getInt("mode") < 0 || tag.getInt("mode") > 2) return null;
        for (String key : new String[]{"source_id", "mirror_id", "claimant", "authority_id",
                "authority_mirror_id", "authority_owner", "bound_by"}) if (!tag.hasUUID(key)) return null;
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
        BindingRef ref = new BindingRef(dimension, BlockPos.of(tag.getLong("source_pos")), tag.getUUID("source_id"),
                tag.getUUID("mirror_id"), tag.getUUID("claimant"), BlockPos.of(tag.getLong("authority_pos")),
                tag.getUUID("authority_id"), tag.getUUID("authority_mirror_id"), tag.getUUID("authority_owner"),
                tag.getUUID("bound_by"), GameType.byId(tag.getInt("mode")));
        return valid(ref) ? ref : null;
    }
}
