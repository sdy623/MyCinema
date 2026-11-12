package local.cinemamanager.qa;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import local.cinemamanager.policy.CinemaPermissions;
import local.cinemamanager.CinemaDisplays;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.mirror.CinemaMirrors;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Loaded-world WF9 publication tests. Both tests complete synchronously and are bounded.
 * The isolated actors use vanilla's GameTestHelper EmbeddedChannel/placeNewPlayer pattern,
 * but retain ServerPlayer's real spectator behavior. No production permission bypass is used.
 * This tests server clocks and publication authority, not client texture/decoder rendering.
 */
@GameTestHolder("mycinema")
@PrefixGameTestTemplate(false)
public final class MirrorSourceGameTests {
    private MirrorSourceGameTests() {}

    private static void isolated(GameTestHelper h) {
        h.assertTrue(Boolean.getBoolean("mycinema.qa")
                        && h.getLevel().getServer() instanceof GameTestServer
                        && h.getLevel().getServer().isSameThread(),
                "mirror fixtures require the opt-in isolated GameTest server thread");
    }

    /** In-memory policy overrides only; no config save and every value restored on failure. */
    private static final class Policy implements AutoCloseable {
        private final Map<String, ModConfigSpec.BooleanValue> values = new LinkedHashMap<>();
        private final Map<String, Boolean> previous = new LinkedHashMap<>();

        Policy(GameTestHelper h) throws ReflectiveOperationException {
            isolated(h);
            for (String name : new String[]{"usePermissionsAPI", "useForAnyone", "useRemotes",
                    "useBindingRemotes", "useInSurvival", "useInAdventure"}) {
                var field = CinemaPermissions.class.getDeclaredField(name);
                field.setAccessible(true);
                var value = (ModConfigSpec.BooleanValue) field.get(null);
                values.put(name, value);
                previous.put(name, value.get());
            }
            try {
                values.forEach((name, value) -> value.set(!name.equals("usePermissionsAPI")));
            } catch (RuntimeException failure) {
                close();
                throw failure;
            }
        }

        void set(String name, boolean value) { values.get(name).set(value); }
        @Override public void close() { previous.forEach((name, value) -> values.get(name).set(value)); }
    }

    /** Random test profiles, actual PlayerList identity, scoped OP entry and network cleanup. */
    private static final class Actor implements AutoCloseable {
        private final PlayerList players;
        private final ServerOpListEntry priorOp;
        private final EmbeddedChannel channel;
        final ServerPlayer player;

        Actor(GameTestHelper h, String name) {
            isolated(h);
            players = h.getLevel().getServer().getPlayerList();
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
            player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
                    cookie.gameProfile(), cookie.clientInformation());
            priorOp = players.getOps().get(player.getGameProfile());
            var connection = new Connection(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(connection);
            try {
                players.placeNewPlayer(connection, player, cookie);
                h.assertTrue(player.level() == h.getLevel()
                                && players.getPlayer(player.getUUID()) == player,
                        "simulated real actor is registered in this exact ServerLevel");
                player.setGameMode(GameType.SURVIVAL);
                player.setPos(h.absolutePos(new BlockPos(3, 2, 3)).getCenter());
                h.assertFalse(CinemaPermissions.isCurrentMirrorAdministrator(player.server, player.getUUID()),
                        "fresh random actor starts without administrator authority");
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        void op(boolean enabled) {
            if (enabled) players.getOps().add(new ServerOpListEntry(player.getGameProfile(), 4, false));
            else players.getOps().remove(player.getGameProfile());
        }

        @Override public void close() {
            try {
                if (priorOp == null) players.getOps().remove(player.getGameProfile());
                else players.getOps().add(priorOp);
            } finally {
                try {
                    if (players.getPlayer(player.getUUID()) == player) players.remove(player);
                } finally {
                    channel.finishAndReleaseAll();
                }
            }
        }
    }

    private static CompoundTag data(DisplayTile tile) {
        var tag = new CompoundTag();
        tile.data.save(tag, tile);
        return tag;
    }

    /** Captures all persisted media/audio controls, epoch, decoder references and clock identity. */
    private static final class Unchanged {
        final DisplayTile tile;
        final CompoundTag data;
        final Object clock, display, mrl;
        final UUID epoch;
        final long position;
        final boolean paused;

        Unchanged(DisplayTile tile) {
            this.tile = tile;
            data = data(tile);
            clock = tile.clock();
            display = tile.display;
            mrl = tile.mrl;
            epoch = tile instanceof CinemaTerminalTile terminal ? terminal.mediaEpoch() : null;
            position = tile.clock() == null ? 0 : tile.clock().time();
            paused = tile.clock() != null && tile.clock().paused();
        }

        void check(GameTestHelper h, String step) {
            h.assertTrue(data.equals(data(tile)), step + ": media, playlist, author, volume and mute unchanged");
            h.assertTrue(clock == tile.clock() && display == tile.display && mrl == tile.mrl
                            && Objects.equals(epoch, tile instanceof CinemaTerminalTile terminal ? terminal.mediaEpoch() : null),
                    step + ": no clock/decoder creation, replacement or epoch rotation");
            h.assertTrue(tile.clock() == null || tile.clock().time() == position && tile.clock().paused() == paused,
                    step + ": paused timeline unchanged");
        }
    }

    private static CinemaMirrors.BindingRef authorize(GameTestHelper h, DisplayTile tile, ServerPlayer actor) {
        var result = CinemaMirrors.authorize(tile, actor, CinemaMirrors.mirrorIdentity(tile));
        h.assertTrue(result.error() == null && result.binding() != null,
                "current nearby owner can publish the loaded source: " + result.error());
        h.assertTrue(CinemaMirrors.valid(result.binding())
                        && CinemaMirrors.resolveLoaded(h.getLevel(), result.binding()) == tile,
                "authorized reference resolves to the exact loaded source");
        return result.binding();
    }

    private static void error(GameTestHelper h, String expected, CinemaMirrors.Result result) {
        h.assertTrue(expected.equals(result.error()) && result.binding() == null,
                "expected " + expected + ", got " + result.error());
    }

    private static void startPaused(DisplayTile tile, UUID author) {
        tile.data.url = "water://wf9-mirror-fixture";
        tile.data.uuid = author;
        tile.data.volume = 37;
        tile.data.muted = true;
        tile.data.paused = true;
        tile.data.loop = false;
        tile.tick();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void mirror_loaded_source_authority_lifetimes_and_current_permissions(GameTestHelper h)
            throws ReflectiveOperationException {
        isolated(h);
        int playersBefore = h.getLevel().getServer().getPlayerList().getPlayerCount();
        try (Policy policy = new Policy(h); Actor owner = new Actor(h, "MirrorOwnerQA");
             Actor other = new Actor(h, "MirrorOtherQA")) {
            h.setBlock(new BlockPos(2, 1, 2), CinemaDisplays.TMS_TERMINAL.get());
            h.setBlock(new BlockPos(4, 1, 2), CinemaDisplays.CINEMA_PROJECTOR.get());
            h.setBlock(new BlockPos(4, 1, 4), CinemaDisplays.CINEMA_PROJECTOR.get());
            CinemaTerminalTile terminal = h.getBlockEntity(new BlockPos(2, 1, 2));
            CinemaProjectorTile source = h.getBlockEntity(new BlockPos(4, 1, 2));
            CinemaProjectorTile second = h.getBlockEntity(new BlockPos(4, 1, 4));
            terminal.owner(owner.player.getUUID());
            source.owner(owner.player.getUUID());
            second.owner(owner.player.getUUID());
            h.assertTrue(terminal.bindNearby(owner.player) == 2 && source.terminal() == terminal
                            && second.terminal() == terminal, "two actual projectors share one reciprocal TMS");
            try {
                startPaused(terminal, owner.player.getUUID());
                h.assertTrue(terminal.clock() != null && terminal.clock().paused()
                                && source.clock() == null && second.clock() == null,
                        "the real paused server clock belongs only to the TMS");
                Unchanged tmsMedia = new Unchanged(terminal), sourceMedia = new Unchanged(source),
                        secondMedia = new Unchanged(second);
                var ref = authorize(h, source, owner.player);
                var tmsRef = authorize(h, terminal, owner.player);
                h.assertTrue(ref.equals(CinemaMirrors.read(CinemaMirrors.write(ref))), "binding NBT round-trips");
                error(h, "existing_theater_ownership", CinemaMirrors.claim(source, owner.player, CinemaMirrors.mirrorIdentity(source)));
                error(h, "existing_theater_ownership", CinemaMirrors.claim(terminal, owner.player, CinemaMirrors.mirrorIdentity(terminal)));
                for (String key : new String[]{"source_id", "mirror_id", "claimant", "authority_id",
                        "authority_mirror_id", "authority_owner"}) {
                    CompoundTag altered = CinemaMirrors.write(ref);
                    altered.putUUID(key, UUID.randomUUID());
                    var mismatched = CinemaMirrors.read(altered);
                    h.assertTrue(CinemaMirrors.valid(mismatched)
                                    && CinemaMirrors.matchLoaded(h.getLevel(), mismatched) == null,
                            "identity field " + key + " must match the current world");
                }
                error(h, "stale_source", CinemaMirrors.authorize(source, owner.player, UUID.randomUUID()));
                error(h, "owner_required", CinemaMirrors.authorize(source, other.player, CinemaMirrors.mirrorIdentity(source)));
                error(h, "owner_required", CinemaMirrors.revoke(source, other.player, CinemaMirrors.mirrorIdentity(source)));
                // Even a same-UUID fake object is not the actor currently registered in PlayerList.
                var impostor = new FakePlayer(h.getLevel(), owner.player.getGameProfile());
                impostor.setPos(source.getBlockPos().getCenter());
                error(h, "invalid_actor_or_source", CinemaMirrors.authorize(source, impostor, CinemaMirrors.mirrorIdentity(source)));
                owner.player.setPos(source.getBlockPos().getCenter().add(8.01, 0, 0));
                error(h, "out_of_range", CinemaMirrors.authorize(source, owner.player, CinemaMirrors.mirrorIdentity(source)));
                owner.player.setPos(h.absolutePos(new BlockPos(3, 2, 3)).getCenter());
                owner.player.setGameMode(GameType.SPECTATOR);
                error(h, "invalid_actor_or_source", CinemaMirrors.authorize(source, owner.player, CinemaMirrors.mirrorIdentity(source)));
                h.assertTrue(CinemaMirrors.resolveLoaded(h.getLevel(), ref) == null,
                        "a live spectator cannot keep the old survival publication");
                owner.player.setGameMode(GameType.SURVIVAL);
                for (String permission : new String[]{"useRemotes", "useBindingRemotes", "useForAnyone", "useInSurvival"}) {
                    policy.set(permission, false);
                    error(h, "permission", CinemaMirrors.authorize(source, owner.player, CinemaMirrors.mirrorIdentity(source)));
                    h.assertTrue(CinemaMirrors.matchLoaded(h.getLevel(), ref) == source
                                    && CinemaMirrors.resolveLoaded(h.getLevel(), ref) == null,
                            "current " + permission + " is rechecked separately from identity");
                    policy.set(permission, true);
                }
                h.assertTrue(CinemaMirrors.revoke(source, owner.player, CinemaMirrors.mirrorIdentity(source)).error() == null
                                && CinemaMirrors.resolveLoaded(h.getLevel(), ref) == null
                                && CinemaMirrors.resolveLoaded(h.getLevel(), tmsRef) == terminal,
                        "source generation revocation invalidates its refs, preserving the TMS reference");
                ref = authorize(h, source, owner.player);
                UUID sourceGeneration = CinemaMirrors.mirrorIdentity(source);
                h.assertTrue(CinemaMirrors.revoke(terminal, owner.player, CinemaMirrors.mirrorIdentity(terminal)).error() == null
                                && sourceGeneration.equals(CinemaMirrors.mirrorIdentity(source))
                                && CinemaMirrors.resolveLoaded(h.getLevel(), ref) == null
                                && CinemaMirrors.resolveLoaded(h.getLevel(), tmsRef) == null,
                        "TMS generation revocation invalidates both references without rotating projector identity");
                var beforeOwnerChange = authorize(h, source, owner.player);
                terminal.owner(other.player.getUUID());
                h.assertTrue(CinemaMirrors.resolveLoaded(h.getLevel(), beforeOwnerChange) == null,
                        "authority owner transfer invalidates the previous reference");
                error(h, "owner_required", CinemaMirrors.authorize(source, owner.player, CinemaMirrors.mirrorIdentity(source)));
                owner.op(true);
                var administratorRef = authorize(h, source, owner.player);
                owner.op(false);
                h.assertTrue(CinemaMirrors.resolveLoaded(h.getLevel(), administratorRef) == null,
                        "removing actual OP authority immediately invalidates an administrator publication");
                terminal.owner(owner.player.getUUID());
                h.assertTrue(CinemaMirrors.resolveLoaded(h.getLevel(), beforeOwnerChange) == null,
                        "returning TMS ownership does not revive the old generation");
                var beforeSourceTransfer = authorize(h, source, owner.player);
                source.owner(other.player.getUUID());
                h.assertTrue(CinemaMirrors.resolveLoaded(h.getLevel(), beforeSourceTransfer) == null,
                        "source owner transfer invalidates the previous reference");
                source.owner(owner.player.getUUID());
                h.assertTrue(CinemaMirrors.resolveLoaded(h.getLevel(), beforeSourceTransfer) == null,
                        "returning source ownership does not revive the old generation");
                var beforeMissingAuthority = authorize(h, source, owner.player);
                BlockPos far = new BlockPos(1000000, 64, 1000000);
                h.assertFalse(h.getLevel().hasChunkAt(far), "far authority starts unloaded");
                int chunks = h.getLevel().getChunkSource().getLoadedChunksCount();
                var unavailable = new CinemaTerminalTile(far, terminal.getBlockState());
                source.bind(unavailable);
                h.assertTrue("authority_unavailable".equals(CinemaMirrors.inspect(source).status())
                                && CinemaMirrors.resolveLoaded(h.getLevel(), beforeMissingAuthority) == null,
                        "unloaded authority fails closed");
                h.assertTrue(!h.getLevel().hasChunkAt(far)
                                && chunks == h.getLevel().getChunkSource().getLoadedChunksCount(),
                        "resolving the missing authority loads no chunks");
                source.bind(terminal);
                terminal.tick(terminal.getBlockState());
                source.tick();
                second.tick();
                tmsMedia.check(h, "all publication and permission changes");
                sourceMedia.check(h, "first projector");
                secondMedia.check(h, "second projector");
            } finally {
                terminal.cleanClock();
                source.cleanClock();
                second.cleanClock();
            }
        }
        h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayerCount() == playersBefore,
                "all registered fixture players were removed");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void mirror_legacy_claim_real_placement_revoke_and_loaded_only_lookup(GameTestHelper h)
            throws ReflectiveOperationException {
        isolated(h);
        int playersBefore = h.getLevel().getServer().getPlayerList().getPlayerCount();
        try (Policy policy = new Policy(h); Actor owner = new Actor(h, "MirrorLegacyQA")) {
            BlockPos legacyPos = new BlockPos(2, 1, 2);
            h.setBlock(legacyPos, CinemaDisplays.TV_BOX.get());
            DisplayTile old = h.getBlockEntity(legacyPos);
            old.data.uuid = owner.player.getUUID();
            old.data.url = "water://legacy-mirror-fixture";
            old.data.volume = 29;
            old.data.paused = true;
            var legacyNbt = old.saveWithFullMetadata(h.getLevel().registryAccess());
            legacyNbt.remove("mirror_identity");
            legacyNbt.remove("mirror_claimant");
            DisplayTile legacy = new DisplayTile(CinemaDisplays.TILE_TV_BOX.get(), old.getBlockPos(), old.getBlockState());
            legacy.loadWithComponents(legacyNbt, h.getLevel().registryAccess());
            h.getLevel().removeBlockEntity(old.getBlockPos());
            h.getLevel().setBlockEntity(legacy);
            DisplayTile placed = null;
            try {
                legacy.tick();
                h.assertTrue(legacy.clock() != null && legacy.clock().paused()
                                && CinemaMirrors.claimant(legacy) == null
                                && owner.player.getUUID().equals(legacy.data.uuid),
                        "old media author is preserved but never inferred as publication claimant");
                Unchanged legacyMedia = new Unchanged(legacy);
                UUID legacyGeneration = CinemaMirrors.mirrorIdentity(legacy);
                error(h, "unclaimed", CinemaMirrors.authorize(legacy, owner.player, legacyGeneration));
                error(h, "administrator_required", CinemaMirrors.claim(legacy, owner.player, legacyGeneration));
                owner.op(true);
                h.assertTrue(CinemaMirrors.claim(legacy, owner.player, legacyGeneration).error() == null
                                && owner.player.getUUID().equals(CinemaMirrors.claimant(legacy))
                                && !legacyGeneration.equals(CinemaMirrors.mirrorIdentity(legacy)),
                        "explicit nearby administrator claim records claimant and rotates publication generation");
                UUID claimedGeneration = CinemaMirrors.mirrorIdentity(legacy);
                h.assertTrue(CinemaMirrors.claim(legacy, owner.player, claimedGeneration).error() == null
                                && claimedGeneration.equals(CinemaMirrors.mirrorIdentity(legacy)),
                        "repeating the same claim is idempotent");
                var claimed = authorize(h, legacy, owner.player);
                owner.op(false);
                h.assertTrue(CinemaMirrors.resolveLoaded(h.getLevel(), claimed) == legacy,
                        "the claimant can continue under normal permissions after administrator status ends");
                legacyMedia.check(h, "legacy administrator claim");

                // Capture a valid description, then move its source into an actually unloaded region.
                BlockPos far = new BlockPos(1000000, 64, 1000000);
                h.assertFalse(h.getLevel().hasChunkAt(far), "far source starts unloaded");
                int chunks = h.getLevel().getChunkSource().getLoadedChunksCount();
                var farNbt = CinemaMirrors.write(claimed);
                farNbt.putLong("source_pos", far.asLong());
                farNbt.putLong("authority_pos", far.asLong());
                var farRef = CinemaMirrors.read(farNbt);
                h.assertTrue(CinemaMirrors.valid(farRef)
                                && CinemaMirrors.matchLoaded(h.getLevel(), farRef) == null
                                && CinemaMirrors.resolveLoaded(h.getLevel(), farRef) == null,
                        "valid but unloaded source references fail without lookup-driven loading");
                h.assertTrue(!h.getLevel().hasChunkAt(far)
                                && chunks == h.getLevel().getChunkSource().getLoadedChunksCount(),
                        "source lookup creates no loaded chunk");
                DisplayTile detached = new DisplayTile(CinemaDisplays.TILE_TV_BOX.get(), legacy.getBlockPos(), legacy.getBlockState());
                detached.loadWithComponents(legacy.saveWithFullMetadata(h.getLevel().registryAccess()), h.getLevel().registryAccess());
                detached.setLevel(h.getLevel());
                h.assertTrue(CinemaMirrors.inspect(detached) == null,
                        "matching saved IDs on a detached tile do not make it the loaded world instance");
                error(h, "invalid_actor_or_source", CinemaMirrors.authorize(detached, owner.player, CinemaMirrors.mirrorIdentity(detached)));

                policy.set("useRemotes", false);
                policy.set("useBindingRemotes", false);
                h.assertTrue(CinemaMirrors.resolveLoaded(h.getLevel(), claimed) == null,
                        "disabling remotes stops the existing publication");
                h.assertTrue(CinemaMirrors.revoke(legacy, owner.player, CinemaMirrors.mirrorIdentity(legacy)).error() == null
                                && CinemaMirrors.claimant(legacy) == null
                                && !claimedGeneration.equals(CinemaMirrors.mirrorIdentity(legacy)),
                        "owner can still revoke a legacy publication while remotes are disabled");
                legacyMedia.check(h, "owner revocation with remotes disabled");

                // The real BlockItem path invokes DisplayBlock.setPlacedBy; never call placedBy manually.
                BlockPos newPos = new BlockPos(5, 1, 2), support = newPos.below();
                h.setBlock(support, Blocks.STONE);
                owner.player.setPos(h.absolutePos(newPos.west(2)).getCenter());
                owner.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CinemaDisplays.TV_BOX_ITEM.get()));
                var use = new UseOnContext(owner.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(h.absolutePos(support).getCenter().add(0, .5, 0),
                                Direction.UP, h.absolutePos(support), false));
                var placement = ((BlockItem) CinemaDisplays.TV_BOX_ITEM.get()).place(new BlockPlaceContext(use));
                h.assertTrue(placement.consumesAction() && h.getBlockState(newPos).is(CinemaDisplays.TV_BOX.get()),
                        "actual registered player item placement succeeds");
                placed = h.getBlockEntity(newPos);
                h.assertTrue(owner.player.getUUID().equals(CinemaMirrors.claimant(placed)) && placed.clock() == null,
                        "real placement records provenance despite disabled remote policy and creates no clock");
                Unchanged placedMedia = new Unchanged(placed);
                error(h, "permission", CinemaMirrors.authorize(placed, owner.player, CinemaMirrors.mirrorIdentity(placed)));
                h.assertTrue(CinemaMirrors.revoke(placed, owner.player, CinemaMirrors.mirrorIdentity(placed)).error() == null
                                && CinemaMirrors.claimant(placed) == null,
                        "new placement owner can withdraw publication while remotes remain disabled");
                placedMedia.check(h, "new placement authorize/revoke");
                policy.set("useRemotes", true);
                policy.set("useBindingRemotes", true);
                h.assertTrue(CinemaMirrors.resolveLoaded(h.getLevel(), claimed) == null,
                        "policy restoration never revives an explicitly revoked source");

                owner.op(true);
                h.assertTrue(CinemaMirrors.claim(legacy, owner.player, CinemaMirrors.mirrorIdentity(legacy)).error() == null,
                        "a fresh explicit claim can create a new publication");
                var beforeReplacement = authorize(h, legacy, owner.player);
                owner.op(false);
                legacyMedia.check(h, "fresh explicit claim");
                DisplayTile replacement = new DisplayTile(CinemaDisplays.TILE_TV_BOX.get(), legacy.getBlockPos(), legacy.getBlockState());
                h.getLevel().removeBlockEntity(legacy.getBlockPos());
                h.getLevel().setBlockEntity(replacement);
                h.assertTrue(legacy.isRemoved() && CinemaMirrors.inspect(legacy) == null
                                && !beforeReplacement.sourceIdentity().equals(CinemaMirrors.mirrorIdentity(replacement))
                                && CinemaMirrors.resolveLoaded(h.getLevel(), beforeReplacement) == null,
                        "removed source and fresh same-position replacement cannot revive the old reference");
                h.assertTrue(replacement.clock() == null && placed.clock() == null,
                        "replacement and publication-only placement have no extra clocks");
            } finally {
                legacy.cleanClock();
                if (placed != null) placed.cleanClock();
            }
        }
        h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayerCount() == playersBefore,
                "fixture player and OP scope have been cleaned up");
        h.succeed();
    }
}
