package local.cinemamanager.qa;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.EnrollmentRegistry;
import local.cinemamanager.ManagerTile;
import local.cinemamanager.media.CinemaSourceEvidence;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.policy.CinemaPermissions;
import local.cinemamanager.CinemaDisplays;
import me.srrapero720.waterframes.common.block.data.DisplayData;
import local.cinemamanager.projection.CinemaProjectorTile;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronous, isolated loaded-world ownership and grant checks. Elevated triplets keep the
 * distant-TMS regression inside this structure's horizontal footprint. No client, decoder,
 * duration observation, network viewer or production ownership bypass is simulated here.
 */
@GameTestHolder("mycinema")
@PrefixGameTestTemplate(false)
public final class HallAuthorizationGameTests {
    private HallAuthorizationGameTests() {}

    private static void isolated(GameTestHelper h) {
        h.assertTrue(Boolean.getBoolean("mycinema.qa")
                        && h.getLevel().getServer() instanceof GameTestServer
                        && h.getLevel().getServer().isSameThread(),
                "hall authorization fixtures require the opt-in isolated GameTest server thread");
    }

    /** In-memory policy changes are restored in the same tick, including assertion failures. */
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
            try { values.forEach((name, value) -> value.set(!name.equals("usePermissionsAPI"))); }
            catch (RuntimeException failure) { close(); throw failure; }
        }

        void set(String name, boolean value) { values.get(name).set(value); }
        @Override public void close() { previous.forEach((name, value) -> values.get(name).set(value)); }
    }

    /** Actual PlayerList registration is necessary for first-claim actor identity checks. */
    private static final class Actor implements AutoCloseable {
        private final PlayerList players;
        private final ServerOpListEntry priorOp;
        private final EmbeddedChannel channel;
        final ServerPlayer player;

        Actor(GameTestHelper h) {
            isolated(h);
            players = h.getLevel().getServer().getPlayerList();
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "HallOwnerQA"), false);
            player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
                    cookie.gameProfile(), cookie.clientInformation());
            priorOp = players.getOps().get(player.getGameProfile());
            var connection = new Connection(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(connection);
            try {
                players.placeNewPlayer(connection, player, cookie);
                player.setGameMode(GameType.SURVIVAL);
                h.assertTrue(players.getPlayer(player.getUUID()) == player && player.level() == h.getLevel(),
                        "actor is the actual online player in the test level");
                h.assertFalse(player.hasPermissions(4), "new random actor has no inherited OP4 permission");
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        void op(boolean value) {
            if (value) players.getOps().add(new ServerOpListEntry(player.getGameProfile(), 4, false));
            else players.getOps().remove(player.getGameProfile());
        }

        @Override public void close() {
            try {
                if (priorOp == null) players.getOps().remove(player.getGameProfile());
                else players.getOps().add(priorOp);
            } finally {
                try { if (players.getPlayer(player.getUUID()) == player) players.remove(player); }
                finally { channel.finishAndReleaseAll(); }
            }
        }
    }

    private static CompoundTag data(DisplayTile tile) {
        CompoundTag tag = new CompoundTag();
        tile.data.save(tag, tile);
        return tag;
    }

    private static CompoundTag dataExceptPause(DisplayTile tile) {
        CompoundTag tag = data(tile);
        tag.remove(DisplayData.PAUSED);
        return tag;
    }

    private static final class Triplet {
        final CinemaTerminalTile terminal;
        final CinemaProjectorTile first, second;
        final ManagerState.Hall hall;

        Triplet(Fixture f, int height) {
            terminal = (CinemaTerminalTile) f.place(new BlockPos(2, height, 2), CinemaDisplays.TMS_TERMINAL.get());
            first = (CinemaProjectorTile) f.place(new BlockPos(2, height, 3), CinemaDisplays.CINEMA_PROJECTOR.get());
            second = (CinemaProjectorTile) f.place(new BlockPos(3, height, 2), CinemaDisplays.CINEMA_PROJECTOR.get());
            f.actor.player.setPos(terminal.getBlockPos().getCenter());
            f.h.assertTrue(terminal.bindNearby(f.actor.player) == 2
                            && first.terminal() == terminal && second.terminal() == terminal,
                    "exact two loaded reciprocal projectors are bound through the ordinary API");
            hall = f.manager.enroll(f.actor.player, terminal, true);
            UUID mediaAuthor = UUID.randomUUID();
            terminal.data.url = "water://hall-claim-fallback";
            terminal.data.playlist.add(new DisplayData.Entry("water://hall-claim-first", 0, mediaAuthor));
            terminal.data.playlist.add(new DisplayData.Entry("water://hall-claim-second", 0, mediaAuthor));
            terminal.data.entryIndex = 1;
            for (CinemaDeviceTile tile : devices()) {
                tile.data.uuid = mediaAuthor;
                tile.data.active = true;
                tile.data.paused = true;
                tile.data.volume = 37;
                tile.data.muted = true;
                tile.data.loop = false;
            }
            first.data.setWidth(9.5f);
            first.data.setHeight(5.25f);
            first.data.projectionDistance = 6;
            first.projectionOffsetX = 1.25f;
            first.projectionOffsetY = 2.5f;
            second.data.setWidth(11.5f);
            second.data.setHeight(6.25f);
            second.data.projectionDistance = 8;
            second.projectionOffsetX = -1.25f;
            second.projectionOffsetY = 3.5f;
            terminal.controlsLinked(false);
            terminal.tick(terminal.getBlockState());
            f.h.assertTrue(terminal.clock() != null && terminal.clock().paused()
                            && first.clock() == null && second.clock() == null,
                    "only the TMS creates a server clock; geometry members have none");
        }

        List<CinemaDeviceTile> devices() { return List.of(terminal, first, second); }
        void owner(UUID owner) { devices().forEach(tile -> tile.owner(owner)); }
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;
        final Actor actor;
        final ManagerTile manager;
        final Triplet selected;
        private final List<BlockPos> elevated = new ArrayList<>();

        Fixture(GameTestHelper h, Actor actor) {
            this.h = h;
            this.actor = actor;
            h.setBlock(new BlockPos(1, 1, 1), CinemaManager.MANAGER.get());
            manager = h.getBlockEntity(new BlockPos(1, 1, 1));
            manager.owner = actor.player.getUUID();
            manager.state.setTimezone("UTC");
            try { selected = new Triplet(this, 32); nearby(); }
            catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        private net.minecraft.world.level.block.entity.BlockEntity place(BlockPos relative, Block block) {
            BlockPos absolute = h.absolutePos(relative);
            h.assertTrue(h.getLevel().hasChunkAt(absolute) && h.getLevel().getBlockState(absolute).isAir(),
                    "elevated fixture requires an already-loaded empty position");
            elevated.add(absolute);
            h.getLevel().setBlockAndUpdate(absolute, block.defaultBlockState());
            return h.getLevel().getBlockEntity(absolute);
        }

        void nearby() { actor.player.setPos(manager.getBlockPos().getCenter()); }
        String fingerprint() {
            var review = manager.claimReview(actor.player, selected.hall.id);
            h.assertTrue(review.status().equals("mycinema.catalog.claim_available")
                            && !review.fingerprint().isBlank() && review.projectors().size() == 2,
                    "review exposes the exact available selected triplet: " + review.status());
            return review.fingerprint();
        }
        void claim(String fingerprint) {
            manager.claimUnownedHall(actor.player, selected.hall.id, manager.state.revision, fingerprint, true);
        }
        void grant() { manager.authorizeProgramme(actor.player, selected.hall.id, true); }

        @Override public void close() {
            EnrollmentRegistry.get(h.getLevel()).releaseAll(manager.identity);
            for (BlockPos pos : elevated) {
                if (h.getLevel().getBlockEntity(pos) instanceof DisplayTile tile) {
                    tile.cleanClock();
                    tile.cleanDisplay();
                }
                h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** Full stable paused NBT plus evidence detects partial writes on every rejected request. */
    private static final class Unchanged {
        private final Fixture f;
        private final CompoundTag manager;
        private final List<CompoundTag> devices;
        private final CompoundTag evidence;
        private final Object clock;

        Unchanged(Fixture f) {
            this.f = f;
            manager = f.manager.saveWithoutMetadata(f.h.getLevel().registryAccess());
            devices = f.selected.devices().stream().map(tile -> tile.saveWithoutMetadata(f.h.getLevel().registryAccess())).toList();
            evidence = CinemaSourceEvidence.get(f.h.getLevel()).save(new CompoundTag(), f.h.getLevel().registryAccess());
            clock = f.selected.terminal.clock();
        }

        void check(String step) {
            f.h.assertTrue(manager.equals(f.manager.saveWithoutMetadata(f.h.getLevel().registryAccess())),
                    step + ": manager owner, catalog, consent, schedule and revision unchanged");
            for (int i = 0; i < devices.size(); i++) {
                f.h.assertTrue(devices.get(i).equals(f.selected.devices().get(i).saveWithoutMetadata(f.h.getLevel().registryAccess())),
                        step + ": device " + i + " ownership, media, geometry, identity and bindings unchanged");
            }
            f.h.assertTrue(clock == f.selected.terminal.clock()
                            && evidence.equals(CinemaSourceEvidence.get(f.h.getLevel()).save(new CompoundTag(), f.h.getLevel().registryAccess())),
                    step + ": no replacement clock or fabricated duration evidence");
        }
    }

    private static void rejects(Fixture f, String suffix, Runnable action) {
        Unchanged before = new Unchanged(f);
        String expected = "mycinema.catalog." + suffix;
        try { action.run(); }
        catch (IllegalArgumentException denied) {
            f.h.assertTrue(expected.equals(denied.getMessage()), "expected " + expected + ", got " + denied.getMessage());
            before.check(expected);
            return;
        }
        throw new AssertionError("request unexpectedly accepted: " + expected);
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void hall_claim_exact_selection_preserves_configuration_and_persists(GameTestHelper h)
            throws ReflectiveOperationException {
        isolated(h);
        int playersBefore = h.getLevel().getServer().getPlayerList().getPlayerCount();
        try (Policy policy = new Policy(h); Actor actor = new Actor(h); Fixture f = new Fixture(h, actor)) {
            actor.op(true);
            Triplet untouched = new Triplet(f, 64);
            var otherBefore = untouched.devices().stream().map(tile -> tile.saveWithoutMetadata(h.getLevel().registryAccess())).toList();
            f.nearby();
            var selected = f.selected;
            var configuration = selected.devices().stream().map(HallAuthorizationGameTests::dataExceptPause).toList();
            var links = selected.terminal.projectors();
            var terminalId = selected.terminal.identity();
            var firstId = selected.first.identity();
            var secondId = selected.second.identity();
            var oldEpoch = selected.terminal.mediaEpoch();
            var evidence = CinemaSourceEvidence.get(h.getLevel()).save(new CompoundTag(), h.getLevel().registryAccess());
            var playbackBefore = new CompoundTag();
            f.manager.playback.save(playbackBefore);
            String fingerprint = f.fingerprint();
            rejects(f, "claim_changed", () -> f.manager.claimUnownedHall(actor.player, untouched.hall.id,
                    f.manager.state.revision, fingerprint, true));
            selected.terminal.data.paused = false;
            selected.terminal.clock().pause(false);
            h.assertTrue(selected.terminal.clock().playing(), "claim begins with an actual playing server clock");
            f.claim(fingerprint);
            for (int i = 0; i < selected.devices().size(); i++) {
                CinemaDeviceTile tile = selected.devices().get(i);
                h.assertTrue(tile.ownedBy(actor.player.getUUID()) && configuration.get(i).equals(dataExceptPause(tile)),
                        "claim assigns exact ownership without replacing source, author, playlist, audio or geometry");
                h.assertTrue(actor.player.getUUID().equals(tile.saveWithoutMetadata(h.getLevel().registryAccess()).getUUID("theater_owner")),
                        "each new owner is present in disk NBT");
                h.assertTrue(otherBefore.get(i).equals(untouched.devices().get(i).saveWithoutMetadata(h.getLevel().registryAccess())),
                        "the other enrolled hall is not claimed or changed");
            }
            h.assertTrue(selected.terminal.data.paused
                            && (selected.terminal.clock() == null || !selected.terminal.clock().playing())
                            && selected.terminal.mediaEpoch() != null && !selected.terminal.mediaEpoch().equals(oldEpoch)
                            && selected.hall.override && selected.hall.transport == ManagerState.Transport.STOPPED,
                    "confirmed first claim holds playback in manual mode and retires the old clock epoch");
            h.assertTrue(terminalId.equals(selected.terminal.identity()) && firstId.equals(selected.first.identity())
                            && secondId.equals(selected.second.identity()) && links.equals(selected.terminal.projectors())
                            && selected.first.terminal() == selected.terminal && selected.second.terminal() == selected.terminal
                            && !selected.terminal.controlsLinked(),
                    "physical identity, reciprocal bindings and linked-control preference survive claim");
            var playbackAfter = new CompoundTag();
            f.manager.playback.save(playbackAfter);
            h.assertTrue(selected.terminal.automationGrant() == null && !f.manager.state.schedulerEnabled
                            && !f.manager.playback.present(selected.hall.id)
                            && !f.manager.playback.localSourceAllowed(terminalId, selected.terminal.data.getUrl())
                            && playbackBefore.get("local_programme_consents").equals(playbackAfter.get("local_programme_consents"))
                            && evidence.equals(CinemaSourceEvidence.get(h.getLevel()).save(new CompoundTag(), h.getLevel().registryAccess()))
                            && selected.terminal.sourceEvidenceId() == null,
                    "ownership creates no automation, local-file consent, programme or duration evidence");
            CinemaTerminalTile restored = new CinemaTerminalTile(selected.terminal.getBlockPos(), selected.terminal.getBlockState());
            restored.loadWithComponents(selected.terminal.saveWithoutMetadata(h.getLevel().registryAccess()), h.getLevel().registryAccess());
            h.assertTrue(restored.ownedBy(actor.player.getUUID()) && restored.projectors().equals(links)
                            && dataExceptPause(restored).equals(configuration.getFirst()) && restored.automationGrant() == null,
                    "detached disk round-trip retains ownership/configuration without granting playback");
            for (CinemaProjectorTile original : List.of(selected.first, selected.second)) {
                CinemaProjectorTile restoredMember = new CinemaProjectorTile(original.getBlockPos(), original.getBlockState());
                restoredMember.loadWithComponents(original.saveWithoutMetadata(h.getLevel().registryAccess()), h.getLevel().registryAccess());
                h.assertTrue(restoredMember.ownedBy(actor.player.getUUID()) && restoredMember.identity().equals(original.identity())
                                && restoredMember.linkedTo(restored) && data(restoredMember).equals(data(original)),
                        "each projector's persisted owner, source/configuration, geometry and reciprocal identity survive reload");
            }
            rejects(f, "claim_tms_owned", () -> f.claim(fingerprint));
        }
        h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayerCount() == playersBefore,
                "test actors and scoped administrator entries are removed synchronously");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void hall_claim_confirmation_revision_and_fingerprint_fail_without_writes(GameTestHelper h)
            throws ReflectiveOperationException {
        try (Policy policy = new Policy(h); Actor actor = new Actor(h); Fixture f = new Fixture(h, actor)) {
            actor.op(true);
            String fingerprint = f.fingerprint();
            rejects(f, "confirm_required", () -> f.manager.claimUnownedHall(actor.player, f.selected.hall.id,
                    f.manager.state.revision, fingerprint, false));
            rejects(f, "stale_manager", () -> f.manager.claimUnownedHall(actor.player, f.selected.hall.id,
                    f.manager.state.revision - 1, fingerprint, true));
            rejects(f, "claim_changed", () -> f.claim("changed-" + fingerprint));
            rejects(f, "claim_changed", () -> f.claim(null));
            rejects(f, "grant_target_unavailable", () -> f.manager.claimUnownedHall(actor.player, UUID.randomUUID().toString(),
                    f.manager.state.revision, fingerprint, true));
            rejects(f, "grant_target_unavailable", () -> f.manager.claimUnownedHall(actor.player, "*",
                    f.manager.state.revision, fingerprint, true));
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void hall_claim_never_transfers_existing_or_mixed_ownership(GameTestHelper h)
            throws ReflectiveOperationException {
        try (Policy policy = new Policy(h); Actor actor = new Actor(h); Fixture f = new Fixture(h, actor)) {
            actor.op(true);
            String fingerprint = f.fingerprint();
            for (UUID existing : List.of(actor.player.getUUID(), UUID.randomUUID())) {
                f.selected.terminal.owner(existing);
                rejects(f, "claim_tms_owned", () -> f.claim(fingerprint));
                f.selected.terminal.owner(null);
                for (CinemaProjectorTile member : List.of(f.selected.first, f.selected.second)) {
                    member.owner(existing);
                    rejects(f, "claim_member_owned", () -> f.claim(fingerprint));
                    member.owner(null);
                }
            }
            f.manager.owner = UUID.randomUUID();
            rejects(f, "grant_manager_owner_required", () -> f.claim(fingerprint));
            f.manager.owner = null;
            rejects(f, "grant_manager_unclaimed", () -> f.claim(fingerprint));
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void hall_claim_requires_current_real_administrator_and_nearby_manager(GameTestHelper h)
            throws ReflectiveOperationException {
        try (Policy policy = new Policy(h); Actor actor = new Actor(h); Fixture f = new Fixture(h, actor)) {
            actor.op(true);
            String fingerprint = f.fingerprint();
            actor.op(false);
            h.assertFalse(actor.player.hasPermissions(4), "administrator permission was actually revoked");
            rejects(f, "claim_operator_required", () -> f.claim(fingerprint));
            actor.op(true);
            actor.player.setPos(f.manager.getBlockPos().getCenter().add(8.01, 0, 0));
            rejects(f, "grant_controller_distance", () -> f.claim(fingerprint));
            f.nearby();
            actor.player.setGameMode(GameType.SPECTATOR);
            rejects(f, "grant_actor_permission", () -> f.claim(fingerprint));
            actor.player.setGameMode(GameType.SURVIVAL);
            FakePlayer impostor = new FakePlayer(h.getLevel(), actor.player.getGameProfile());
            impostor.setPos(f.manager.getBlockPos().getCenter());
            rejects(f, "grant_actor_permission", () -> f.manager.claimUnownedHall(impostor, f.selected.hall.id,
                    f.manager.state.revision, fingerprint, true));
            actor.player.setPos(f.manager.getBlockPos().getCenter().add(8, 0, 0));
            h.assertTrue(actor.player.distanceToSqr(f.selected.terminal.getBlockPos().getCenter()) > 16 * 16,
                    "positive boundary remains more than sixteen blocks from the TMS");
            f.claim(fingerprint);
            h.assertTrue(f.selected.terminal.ownedBy(actor.player.getUUID()), "exact eight-block manager boundary permits claim");
        }
        h.succeed();
    }

    @SuppressWarnings("unchecked")
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void hall_claim_stale_enrollment_and_unavailable_members_fail_atomically(GameTestHelper h)
            throws ReflectiveOperationException {
        try (Policy policy = new Policy(h); Actor actor = new Actor(h); Fixture f = new Fixture(h, actor)) {
            actor.op(true);
            String fingerprint = f.fingerprint();
            var registry = EnrollmentRegistry.get(h.getLevel());
            registry.release(f.selected.terminal.identity(), f.manager.identity);
            rejects(f, "grant_target_unavailable", () -> f.claim(fingerprint));
            h.assertTrue(registry.claim(f.selected.terminal.identity(), f.manager.identity), "restore fixture enrollment");
            String identity = f.selected.hall.terminalIdentity;
            f.selected.hall.terminalIdentity = UUID.randomUUID().toString();
            rejects(f, "grant_target_unavailable", () -> f.claim(fingerprint));
            f.selected.hall.terminalIdentity = identity;
            f.selected.second.bind(null);
            rejects(f, "claim_member_changed", () -> f.claim(fingerprint));
            f.selected.second.bind(f.selected.terminal);

            // A bounded QA-only unresolved-link fixture avoids unloading another test's chunk.
            // Restored in finally; no production method or world chunk is replaced or loaded.
            var field = CinemaTerminalTile.class.getDeclaredField("projectors");
            field.setAccessible(true);
            Map<BlockPos, UUID> links = (Map<BlockPos, UUID>) field.get(f.selected.terminal);
            var original = new LinkedHashMap<>(links);
            try {
                links.remove(f.selected.second.getBlockPos());
                rejects(f, "claim_members_required", () -> f.claim(fingerprint));
                BlockPos unloaded = new BlockPos(25_000_000, f.selected.terminal.getBlockPos().getY(), 25_000_000);
                h.assertFalse(h.getLevel().hasChunkAt(unloaded), "unloaded fixture position has no existing chunk");
                links.put(unloaded, f.selected.second.identity());
                rejects(f, "claim_member_unloaded", () -> f.claim(fingerprint));
                h.assertFalse(h.getLevel().hasChunkAt(unloaded), "rejection does not load the absent member chunk");
            } finally { links.clear(); links.putAll(original); }
            f.claim(fingerprint);
            h.assertTrue(f.selected.first.ownedBy(actor.player.getUUID()) && f.selected.second.ownedBy(actor.player.getUUID()),
                    "restoring the exact reviewed triplet permits the selected claim");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void hall_programme_grant_uses_manager_distance_and_reports_exact_owners(GameTestHelper h)
            throws ReflectiveOperationException {
        try (Policy policy = new Policy(h); Actor actor = new Actor(h); Fixture f = new Fixture(h, actor)) {
            f.selected.owner(actor.player.getUUID());
            actor.player.setPos(f.manager.getBlockPos().getCenter().add(8, 0, 0));
            h.assertTrue(actor.player.distanceToSqr(f.selected.terminal.getBlockPos().getCenter()) > 16 * 16,
                    "owned enrolled TMS is over sixteen blocks away while manager is exactly eight away");
            f.grant();
            var grant = f.selected.terminal.automationGrant();
            h.assertTrue(grant != null && grant.matches(f.selected.terminal.identity(), f.manager.identity,
                            actor.player.getUUID(), f.manager.getBlockPos()) && !f.manager.state.schedulerEnabled,
                    "ordinary owner grants controller authority without enabling scheduling or TMS proximity");
            f.selected.terminal.revokeAutomation();
            actor.player.setPos(f.manager.getBlockPos().getCenter().add(8.01, 0, 0));
            rejects(f, "grant_controller_distance", f::grant);
            f.nearby();
            rejects(f, "confirm_required", () -> f.manager.authorizeProgramme(actor.player, f.selected.hall.id, false));
            f.manager.owner = null;
            rejects(f, "grant_manager_unclaimed", f::grant);
            f.manager.owner = UUID.randomUUID();
            actor.op(true);
            rejects(f, "grant_manager_owner_required", f::grant);
            f.manager.owner = actor.player.getUUID();
            f.selected.terminal.owner(null);
            rejects(f, "grant_tms_unclaimed", f::grant);
            f.selected.terminal.owner(UUID.randomUUID());
            rejects(f, "grant_tms_owner_required", f::grant);
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void hall_programme_grant_rechecks_current_config_and_target(GameTestHelper h)
            throws ReflectiveOperationException {
        try (Policy policy = new Policy(h); Actor actor = new Actor(h); Fixture f = new Fixture(h, actor)) {
            f.selected.owner(actor.player.getUUID());
            for (String[] denied : List.of(new String[]{"useBindingRemotes", "grant_bind_permission"},
                    new String[]{"useRemotes", "grant_remote_permission"},
                    new String[]{"useForAnyone", "grant_block_permission"},
                    new String[]{"useInSurvival", "grant_block_permission"})) {
                policy.set(denied[0], false);
                try { rejects(f, denied[1], f::grant); }
                finally { policy.set(denied[0], true); }
            }
            actor.op(true);
            for (String name : List.of("useForAnyone", "useRemotes", "useBindingRemotes", "useInSurvival")) {
                policy.set(name, false);
                try { rejects(f, "grant_automation_policy", f::grant); }
                finally { policy.set(name, true); }
            }
            rejects(f, "grant_hall_missing", () -> f.manager.authorizeProgramme(actor.player, UUID.randomUUID().toString(), true));
            var registry = EnrollmentRegistry.get(h.getLevel());
            registry.release(f.selected.terminal.identity(), f.manager.identity);
            rejects(f, "grant_enrollment_changed", f::grant);
            h.assertTrue(registry.claim(f.selected.terminal.identity(), f.manager.identity), "restore fixture enrollment");
            String identity = f.selected.hall.terminalIdentity;
            f.selected.hall.terminalIdentity = UUID.randomUUID().toString();
            rejects(f, "grant_tms_changed", f::grant);
            f.selected.hall.terminalIdentity = identity;
            long position = f.selected.hall.terminalPos;
            BlockPos unloaded = new BlockPos(25_000_000, 64, 25_000_000);
            h.assertFalse(h.getLevel().hasChunkAt(unloaded), "target fixture chunk starts unloaded");
            f.selected.hall.terminalPos = unloaded.asLong();
            rejects(f, "grant_tms_unloaded", f::grant);
            h.assertFalse(h.getLevel().hasChunkAt(unloaded), "grant rejection does not create a chunk ticket");
            f.selected.hall.terminalPos = position;
            f.grant();
            h.assertTrue(f.selected.terminal.automationGrant() != null, "current valid policy and exact enrollment restore grant eligibility");
        }
        h.succeed();
    }
}
