package local.cinemamanager.qa;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import local.cinemamanager.*;
import local.cinemamanager.access.TabletAccess;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.operations.*;
import local.cinemamanager.panels.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Source-only candidate checks; these tests have not been compiled or executed yet.
 * Real PlayerList actors exercise the production adapter. No decoder, client, source evidence,
 * programme or production authority bypass is created by this fixture.
 */
@GameTestHolder("mycinema")
@PrefixGameTestTemplate(false)
public final class OperationsScreenGameTests {
    private OperationsScreenGameTests() {}

    private static void isolated(GameTestHelper h) {
        h.assertTrue(Boolean.getBoolean("mycinema.qa")
                        && h.getLevel().getServer() instanceof GameTestServer
                        && h.getLevel().getServer().isSameThread(),
                "operations fixtures require the opt-in isolated GameTest server thread");
    }

    /** Same actual-online-player pattern as HallAuthorizationGameTests. */
    private static final class Actor implements AutoCloseable {
        final PlayerList players;
        final ServerPlayer player;
        private final ServerOpListEntry priorOp;
        private final EmbeddedChannel channel;

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
                player.setGameMode(GameType.SURVIVAL);
                h.assertTrue(players.getPlayer(player.getUUID()) == player && player.level() == h.getLevel(),
                        "fixture actor is the actual online ServerPlayer in this test level");
                h.assertFalse(player.hasPermissions(2), "random test actor starts without operator privileges");
            } catch (RuntimeException | Error failure) { close(); throw failure; }
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
                try { if (players.getPlayer(player.getUUID()) == player) players.remove(player); }
                finally { channel.finishAndReleaseAll(); }
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper h;
        final Actor actor;
        final ManagerTile manager;
        final InfoTile panel;
        final ManagerState.Hall hall;
        final ManagerState.Show show;
        final List<BlockPos> elevated = new ArrayList<>();
        final List<ItemStack> activations = new ArrayList<>();

        Fixture(GameTestHelper h, Actor actor) {
            this.h = h;
            this.actor = actor;
            h.setBlock(new BlockPos(1, 1, 1), CinemaManager.MANAGER.get());
            manager = h.getBlockEntity(new BlockPos(1, 1, 1));
            manager.initializePlacement(actor.player.getUUID());
            manager.state.setTimezone("UTC");
            hall = new ManagerState.Hall();
            hall.name = "Operations QA Hall";
            hall.terminalIdentity = UUID.randomUUID().toString();
            hall.terminalPos = h.absolutePos(new BlockPos(7, 1, 1)).asLong();
            manager.state.halls.add(hall);
            show = new ManagerState.Show();
            show.hallId = hall.id;
            show.title = "Operations QA Show";
            show.url = "https://operations-qa.invalid/private-source.mkv";
            show.start = System.currentTimeMillis() - 60_000;
            show.end = show.start + 3_600_000;
            manager.state.shows.add(show);
            h.setBlock(new BlockPos(2, 1, 1), CinemaManager.INFO.get());
            panel = h.getBlockEntity(new BlockPos(2, 1, 1));
            panel.initializePlacement(actor.player.getUUID());
            configure(panel);
        }

        void configure(InfoTile target) {
            actor.player.setPos(target.getBlockPos().getCenter());
            target.bind(actor.player, manager, hall.id);
            target.configure(actor.player, PanelGroup.of(target).signature(), target.panel,
                    PanelScope.HALL, TerminalRoleSettings.OPERATIONS);
        }

        InfoTile remotePanel() {
            BlockPos absolute = h.absolutePos(new BlockPos(2, 33, 1));
            h.assertTrue(h.getLevel().hasChunkAt(absolute) && h.getLevel().getBlockState(absolute).isAir(),
                    "remote panel fixture uses a loaded empty position inside the horizontal footprint");
            elevated.add(absolute);
            h.getLevel().setBlockAndUpdate(absolute, CinemaManager.INFO.get().defaultBlockState());
            InfoTile remote = (InfoTile) h.getLevel().getBlockEntity(absolute);
            remote.initializePlacement(actor.player.getUUID());
            configure(remote);
            return remote;
        }

        ItemStack activateTablet() {
            actor.player.setPos(manager.getBlockPos().getCenter());
            ItemStack tablet = new ItemStack(CinemaManager.TABLET.get());
            actor.player.setItemInHand(InteractionHand.MAIN_HAND, tablet);
            CompoundTag binding = new CompoundTag();
            TabletAccess.activate(actor.player, manager, tablet, binding);
            tablet.set(DataComponents.CUSTOM_DATA, CustomData.of(binding));
            activations.add(tablet);
            h.assertTrue(TabletAccess.registeredHeld(actor.player, manager),
                    "ordinary local activation creates a live server-owned tablet registration");
            return tablet;
        }

        @Override public void close() {
            for (ItemStack tablet : activations) TabletAccess.revoke(actor.player, tablet);
            for (BlockPos pos : elevated) h.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
    }

    private static OperationsNetwork.Arguments args(Fixture f, InfoTile panel) {
        var data = OperationsNetwork.data(f.actor.player, panel, "");
        f.h.assertTrue(data.authorized(), "owner snapshot is authorized before capturing request fields");
        var args = new OperationsNetwork.Arguments();
        args.controller = data.controller();
        args.panelRevision = data.panelRevision();
        args.signature = data.signature();
        args.managerRevision = data.managerRevision();
        args.hall = f.hall.id;
        args.show = f.show.id;
        return args;
    }

    private static void rejects(GameTestHelper h, String suffix, Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException denied) {
            h.assertTrue(denied.getMessage() != null
                            && (suffix == null || denied.getMessage().equals("mycinema.operations." + suffix)),
                    "expected operations refusal " + suffix + ", got " + denied.getMessage());
            return;
        }
        throw new AssertionError("request unexpectedly accepted: " + suffix);
    }

    private static void deniedWithoutMutation(Fixture f, String suffix, Runnable action) {
        CompoundTag manager = f.manager.saveWithoutMetadata(f.h.getLevel().registryAccess());
        CompoundTag panel = f.panel.saveWithoutMetadata(f.h.getLevel().registryAccess());
        rejects(f.h, suffix, action);
        f.h.assertTrue(manager.equals(f.manager.saveWithoutMetadata(f.h.getLevel().registryAccess()))
                        && panel.equals(f.panel.saveWithoutMetadata(f.h.getLevel().registryAccess())),
                "refused operations authority check changes neither manager nor panel saved state");
    }

    private static OperationsNetwork.Data reply(GameTestHelper h, ServerPlayer actor, InfoTile panel,
                                                 String identity, String action, String request, String json) {
        var result = OperationsNetwork.execute(actor,
                new OperationsNetwork.Request(panel.getBlockPos(), identity, action, request, json));
        h.assertTrue(result != null && !result.open() && result.request().equals(request)
                        && result.pos().equals(panel.getBlockPos()) && result.identity().equals(identity),
                "packet result acknowledges the exact source, identity and request");
        return OperationsNetwork.JSON.fromJson(result.json(), OperationsNetwork.Data.class);
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void operations_owner_online_identity_and_nonowner_operator_refusal(GameTestHelper h) {
        isolated(h);
        int before = h.getLevel().getServer().getPlayerList().getPlayerCount();
        try (Actor owner = new Actor(h, "OpsOwnerQA"); Actor visitor = new Actor(h, "OpsVisitorQA");
             Fixture f = new Fixture(h, owner)) {
            visitor.player.setPos(f.panel.getBlockPos().getCenter());
            h.assertTrue(OperationsAccess.requireView(owner.player, f.panel) == f.manager
                            && OperationsAccess.requireControl(owner.player, f.panel, args(f, f.panel)) == f.manager,
                    "actual local owner may view and reach the existing manager control service");
            deniedWithoutMutation(f, "owner_required", () -> OperationsAccess.requireView(visitor.player, f.panel));
            visitor.op(true);
            h.assertTrue(f.manager.permits(visitor.player, Double.POSITIVE_INFINITY),
                    "fixture confirms the existing general OP override before checking operations refusal");
            deniedWithoutMutation(f, "owner_required", () -> OperationsAccess.requireView(visitor.player, f.panel));
            var forged = args(f, f.panel);
            deniedWithoutMutation(f, "owner_required", () -> OperationsAccess.requireControl(visitor.player, f.panel, forged));
            var denied = OperationsNetwork.data(visitor.player, f.panel, "");
            h.assertTrue(!denied.authorized() && !denied.controllable() && denied.halls().isEmpty()
                            && denied.plans().isEmpty() && denied.controller().isBlank(),
                    "nonowner OP receives no actor-specific operations identities or plans");
            owner.player.setGameMode(GameType.SPECTATOR);
            deniedWithoutMutation(f, "permission", () -> OperationsAccess.requireView(owner.player, f.panel));
            owner.player.setGameMode(GameType.SURVIVAL);
            var fake = new FakePlayer(h.getLevel(), owner.player.getGameProfile());
            fake.setPos(f.panel.getBlockPos().getCenter());
            deniedWithoutMutation(f, "permission", () -> OperationsAccess.requireView(fake, f.panel));
            var detached = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
                    owner.player.getGameProfile(), net.minecraft.server.level.ClientInformation.createDefault());
            detached.connection = owner.player.connection;
            detached.setPos(f.panel.getBlockPos().getCenter());
            deniedWithoutMutation(f, "permission", () -> OperationsAccess.requireView(detached, f.panel));
            owner.players.remove(owner.player);
            deniedWithoutMutation(f, "permission", () -> OperationsAccess.requireView(owner.player, f.panel));
        }
        h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayerCount() == before,
                "online actor fixtures restore the original player count");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void operations_rechecks_revision_role_owners_and_controller_on_every_request(GameTestHelper h) {
        isolated(h);
        try (Actor actor = new Actor(h, "OpsRevokeQA"); Fixture f = new Fixture(h, actor)) {
            var staleRevision = args(f, f.panel);
            f.panel.panelRevision++;
            deniedWithoutMutation(f, "stale_panel", () -> OperationsAccess.requireControl(actor.player, f.panel, staleRevision));
            var badSignature = args(f, f.panel);
            badSignature.signature = UUID.randomUUID().toString();
            deniedWithoutMutation(f, "stale_panel", () -> OperationsAccess.requireControl(actor.player, f.panel, badSignature));
            var wrongController = args(f, f.panel);
            wrongController.controller = UUID.randomUUID().toString();
            deniedWithoutMutation(f, "stale_panel", () -> OperationsAccess.requireControl(actor.player, f.panel, wrongController));
            var wrongHall = args(f, f.panel);
            wrongHall.hall = UUID.randomUUID().toString();
            deniedWithoutMutation(f, "wrong_hall", () -> OperationsAccess.requireControl(actor.player, f.panel, wrongHall));

            String oldIdentity = f.panel.identity.toString();
            f.panel.identity = UUID.randomUUID();
            var stale = reply(h, actor.player, f.panel, oldIdentity, "view", UUID.randomUUID().toString(), "{}");
            h.assertFalse(stale.authorized(), "replaced panel identity invalidates an old packet");
            f.panel.terminalRole = TerminalRoleSettings.LEGACY;
            deniedWithoutMutation(f, "not_operations", () -> OperationsAccess.requireView(actor.player, f.panel));
            f.panel.terminalRole = TerminalRoleSettings.OPERATIONS;
            UUID owner = f.panel.owner;
            f.panel.owner = UUID.randomUUID();
            deniedWithoutMutation(f, "stale_binding", () -> OperationsAccess.requireView(actor.player, f.panel));
            f.panel.owner = owner;
            f.manager.owner = UUID.randomUUID();
            deniedWithoutMutation(f, "stale_binding", () -> OperationsAccess.requireView(actor.player, f.panel));
            f.manager.owner = owner;
            UUID controller = f.manager.identity;
            f.manager.identity = UUID.randomUUID();
            deniedWithoutMutation(f, "unavailable", () -> OperationsAccess.requireView(actor.player, f.panel));
            f.manager.identity = controller;
            BlockPos missing = new BlockPos(1_000_000, 80, 1_000_000);
            h.assertFalse(h.getLevel().hasChunkAt(missing), "missing manager target begins unloaded");
            f.panel.manager = missing;
            deniedWithoutMutation(f, "unavailable", () -> OperationsAccess.requireView(actor.player, f.panel));
            h.assertFalse(h.getLevel().hasChunkAt(missing), "operations lookup does not request the missing chunk");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void operations_remote_tablet_requires_live_held_registration(GameTestHelper h) {
        isolated(h);
        try (Actor actor = new Actor(h, "OpsTabletQA"); Fixture f = new Fixture(h, actor)) {
            InfoTile remote = f.remotePanel();
            var expected = args(f, remote);
            h.assertTrue(actor.player.distanceToSqr(f.manager.getBlockPos().getCenter()) > 64,
                    "operations console lies outside the manager's ordinary eight-block reach");
            rejects(h, "manager_range", () -> OperationsAccess.requireControl(actor.player, remote, expected));
            h.assertFalse(OperationsNetwork.data(actor.player, remote, "").controllable(),
                    "owner-only remote view does not itself grant remote control");

            CompoundTag copiedBinding = new CompoundTag();
            copiedBinding.putLong("manager", f.manager.getBlockPos().asLong());
            copiedBinding.putUUID("identity", f.manager.identity);
            copiedBinding.putString("dimension", h.getLevel().dimension().location().toString());
            copiedBinding.putUUID("tabletAccess", UUID.randomUUID());
            copiedBinding.putUUID("tabletPlayer", actor.player.getUUID());
            ItemStack forged = new ItemStack(CinemaManager.TABLET.get());
            forged.set(DataComponents.CUSTOM_DATA, CustomData.of(copiedBinding));
            actor.player.setItemInHand(InteractionHand.MAIN_HAND, forged);
            h.assertTrue(TabletItem.boundTo(actor.player, f.manager),
                    "forged item deliberately satisfies the unchanged legacy media binding helper");
            h.assertFalse(TabletAccess.registeredHeld(actor.player, f.manager),
                    "invented token has no server-owned registration");
            rejects(h, "manager_range", () -> OperationsAccess.requireControl(actor.player, remote, expected));
            h.assertFalse(OperationsNetwork.data(actor.player, remote, "").controllable(),
                    "UI reach agrees with the forged-token refusal");

            ItemStack tablet = f.activateTablet();
            actor.player.setPos(remote.getBlockPos().getCenter());
            h.assertTrue(OperationsAccess.requireControl(actor.player, remote, expected) == f.manager
                            && OperationsNetwork.data(actor.player, remote, "").controllable(),
                    "local activation permits the unchanged manager service from a remote operations console");
            actor.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            actor.player.getInventory().setItem(9, tablet);
            rejects(h, "manager_range", () -> OperationsAccess.requireControl(actor.player, remote, expected));
            actor.player.getInventory().setItem(9, ItemStack.EMPTY);
            actor.player.setItemInHand(InteractionHand.OFF_HAND, tablet);
            h.assertTrue(OperationsAccess.requireControl(actor.player, remote, expected) == f.manager,
                    "registered offhand tablet follows the existing held-tablet route");
            ItemStack paper = new ItemStack(Items.PAPER);
            paper.set(DataComponents.CUSTOM_DATA, tablet.get(DataComponents.CUSTOM_DATA));
            actor.player.setItemInHand(InteractionHand.OFF_HAND, paper);
            rejects(h, "manager_range", () -> OperationsAccess.requireControl(actor.player, remote, expected));
            actor.player.setItemInHand(InteractionHand.OFF_HAND, tablet);
            TabletAccess.revoke(actor.player, tablet);
            rejects(h, "manager_range", () -> OperationsAccess.requireControl(actor.player, remote, expected));
            h.assertFalse(OperationsNetwork.data(actor.player, remote, "").controllable(),
                    "revocation disables operations controls on the next server snapshot");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void operations_packets_acknowledge_refusal_without_loading_or_replay(GameTestHelper h) {
        isolated(h);
        try (Actor actor = new Actor(h, "OpsPacketQA"); Fixture f = new Fixture(h, actor)) {
            CompoundTag before = f.manager.saveWithoutMetadata(h.getLevel().registryAccess());
            var args = args(f, f.panel);
            String id = UUID.randomUUID().toString();
            String json = OperationsNetwork.JSON.toJson(args);
            var first = reply(h, actor.player, f.panel, f.panel.identity.toString(), "load_show", id, json);
            h.assertTrue(first.notice().equals("mycinema.operations.confirm_required"),
                    "unconfirmed show load is refused before the manager media service");
            var duplicate = reply(h, actor.player, f.panel, f.panel.identity.toString(), "load_show", id, json);
            h.assertTrue(duplicate.notice().equals("mycinema.operations.duplicate"),
                    "a repeated mutation request cannot replay the service call");
            args.confirmed = true;
            args.managerRevision--;
            var revision = reply(h, actor.player, f.panel, f.panel.identity.toString(), "load_show",
                    UUID.randomUUID().toString(), OperationsNetwork.JSON.toJson(args));
            h.assertFalse(revision.notice().equals("mycinema.operations.show_loaded"),
                    "stale schedule revision never loads a source");
            var invalid = reply(h, actor.player, f.panel, f.panel.identity.toString(), "grant",
                    UUID.randomUUID().toString(), "{}");
            h.assertTrue(invalid.notice().equals("mycinema.operations.invalid"),
                    "client cannot invent an operations grant action");
            h.assertTrue(before.equals(f.manager.saveWithoutMetadata(h.getLevel().registryAccess())),
                    "all refused requests preserve saved playback, schedule, source and grant state");
            h.assertTrue(f.manager.playback.publicProgramme(f.hall.id) == null,
                    "adapter refusals never fabricate a programme");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void operations_saved_roles_preserve_unknown_data_and_reset_item_authority(GameTestHelper h) {
        isolated(h);
        try (Actor actor = new Actor(h, "OpsSaveQA"); Fixture f = new Fixture(h, actor)) {
            CompoundTag saved = f.panel.saveWithFullMetadata(h.getLevel().registryAccess());
            InfoTile restored = copy(h, f.panel, saved);
            h.assertTrue(restored.isOperations() && restored.terminalRole.equals(TerminalRoleSettings.OPERATIONS)
                            && restored.owner.equals(f.panel.owner) && restored.managerIdentity.equals(f.manager.identity)
                            && restored.hallId.equals(f.hall.id) && restored.panelRevision == f.panel.panelRevision,
                    "saved operations role, owner, exact controller, hall and revision round-trip");
            CompoundTag legacy = saved.copy();
            legacy.remove("terminal_role");
            restored = copy(h, f.panel, legacy);
            h.assertTrue(restored.isAudienceInformation() && !restored.isOperations()
                            && restored.terminalRole.equals(TerminalRoleSettings.LEGACY)
                            && restored.hallId.equals(f.hall.id),
                    "pre-role world NBT loads as the existing audience programme without losing its binding");
            for (Tag bad : List.of(StringTag.valueOf("{\"schema\":2,\"role\":\"OPERATIONS\",\"view\":\"OPERATIONS\"}"),
                    StringTag.valueOf("{broken"), IntTag.valueOf(1))) {
                CompoundTag invalid = saved.copy();
                invalid.put("terminal_role", bad.copy());
                InfoTile unknown = copy(h, f.panel, invalid);
                h.assertTrue(unknown.invalidTerminalRole() && !unknown.isOperations() && !unknown.isAudienceInformation(),
                        "malformed or future role data fails closed for both public and operations entry");
                h.assertTrue(bad.equals(unknown.saveWithoutMetadata(h.getLevel().registryAccess()).get("terminal_role")),
                        "unknown role data survives save unchanged for a later compatible reader");
                f.panel.loadWithComponents(invalid, h.getLevel().registryAccess());
                deniedWithoutMutation(f, "invalid_role", () -> OperationsAccess.requireView(actor.player, f.panel));
            }
            f.panel.loadWithComponents(saved, h.getLevel().registryAccess());
            CompoundTag picked = saved.copy();
            f.panel.removeComponentsFromTag(picked);
            h.assertTrue(picked.getAllKeys().equals(java.util.Set.of("panel_settings", "panel_scope")),
                    "creative pick sanitization strips saved role, owner, manager, identity and grants");
            UUID old = f.panel.identity;
            f.panel.initializePlacement(actor.player.getUUID());
            h.assertTrue(f.panel.isAudienceInformation() && !f.panel.invalidTerminalRole()
                            && !f.panel.identity.equals(old) && f.panel.manager == null
                            && f.panel.managerIdentity == null && f.panel.hallId.isEmpty(),
                    "real placement resets copied saved authority to a fresh audience panel");
        }
        h.succeed();
    }

    private static InfoTile copy(GameTestHelper h, InfoTile source, CompoundTag tag) {
        InfoTile copy = new InfoTile(source.getBlockPos(), source.getBlockState());
        copy.loadWithComponents(tag, h.getLevel().registryAccess());
        return copy;
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void operations_public_projection_redacts_sources_and_preserves_audience_return(GameTestHelper h) {
        isolated(h);
        try (Actor actor = new Actor(h, "OpsPublicQA"); Fixture f = new Fixture(h, actor)) {
            f.manager.state.name = "https://private-cinema.invalid";
            f.hall.name = "/private/hall";
            f.show.title = "token=private-show";
            f.hall.seekToken = "private-seek-ticket";
            f.hall.issue = "private-source-diagnostic";
            f.manager.state.notice = "private-manager-diagnostic";
            var overrides = new PanelSettings(PanelSettings.Layout.POSTER_TOP,
                    "private-hall-override", "password=private-panel", "private-time", "private-next", "private-footer", "",
                    0x112233, 0x010203, 0xabcdef, 100, PanelSettings.ALL);
            f.panel.configure(actor.player, PanelGroup.of(f.panel).signature(), overrides,
                    PanelScope.HALL, TerminalRoleSettings.OPERATIONS);
            CompoundTag update = f.panel.getUpdateTag(h.getLevel().registryAccess());
            String publicText = update.toString();
            for (String privateValue : List.of(f.manager.identity.toString(), f.manager.owner.toString(), f.hall.id,
                    f.hall.terminalIdentity, f.show.id, f.show.url, "private-cinema", "private-show", "private-panel",
                    "private-hall-override", "private-seek-ticket", "private-source-diagnostic", "private-manager-diagnostic"))
                h.assertFalse(publicText.contains(privateValue), "operations block update excludes private value: " + privateValue);
            for (String privateKey : List.of("owner", "manager", "manager_id", "hall", "poster_grant", "film_programmes", "local_programme_consents"))
                h.assertFalse(update.contains(privateKey), "public tag excludes saved authority key " + privateKey);
            var snapshot = PublicOperationsSnapshot.read(update.getCompound("operations_snapshot"));
            h.assertTrue(snapshot.halls().size() == 1 && snapshot.plans().size() == 1
                            && snapshot.cinema().equals("MyCinema") && snapshot.halls().getFirst().name().equals("Hall")
                            && snapshot.plans().getFirst().title().equals("Programme"),
                    "wall uses the bounded source-free projection with redacted authored labels");
            var publicSettings = PanelSettings.read(update.getCompound("panel_settings"));
            h.assertTrue(publicSettings.layout() == PanelSettings.Layout.TEXT && publicSettings.title().isBlank()
                            && publicSettings.hall().isBlank() && publicSettings.poster().isBlank()
                            && publicSettings.foreground() == overrides.foreground(),
                    "operations public settings contain visual choices without old audience overrides");
            var guest = PanelNetwork.guestData(f.panel);
            h.assertTrue(guest.snapshot().rows().isEmpty() && guest.settings().poster().isBlank()
                            && guest.settings().title().isBlank() && !f.panel.isAudienceInformation(),
                    "operations role never falls through to the audience schedule or seat-map hook");
            InfoTile publicCopy = copy(h, f.panel, update);
            h.assertTrue(publicCopy.isOperations() && publicCopy.owner == null && publicCopy.managerIdentity == null
                            && publicCopy.operationsSnapshot.equals(snapshot),
                    "public client NBT can render operations without acquiring owner or manager authority");
            f.manager.state.name = "Audience Cinema";
            f.hall.name = "Audience Hall";
            f.show.title = "Audience Programme";
            f.panel.configure(actor.player, PanelGroup.of(f.panel).signature(), overrides,
                    PanelScope.HALL, TerminalRoleSettings.LEGACY);
            var audience = PanelNetwork.guestData(f.panel);
            h.assertTrue(f.panel.isAudienceInformation() && !f.panel.isOperations()
                            && f.panel.hallId.equals(f.hall.id) && f.panel.panel.equals(overrides)
                            && audience.snapshot().rows().size() == 1
                            && audience.snapshot().rows().getFirst().title().equals("Audience Programme"),
                    "returning to audience HALL retains its binding, layout and actual public programme rows");
            h.assertFalse(f.panel.getUpdateTag(h.getLevel().registryAccess()).contains("operations_snapshot"),
                    "audience wall updates no longer carry operations rows");
            rejects(h, null, () -> new PublicOperationsSnapshot("Cinema", "UTC", 0, "AVAILABLE",
                    Collections.nCopies(ManagerState.MAX_HALLS + 1,
                            new PublicOperationsSnapshot.Hall("Hall", "NO_PROGRAMME", "", 0, 0)), List.of()));
            rejects(h, null, () -> new PublicOperationsSnapshot.Hall("Hall", "PLAYING", "Playlist", 65, 64));
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void operations_wall_groups_partition_role_owner_and_public_root(GameTestHelper h) {
        isolated(h);
        try (Actor actor = new Actor(h, "OpsWallQA"); Fixture f = new Fixture(h, actor)) {
            InfoTile first = wall(h, actor.player, new BlockPos(4, 1, 2));
            f.configure(first);
            InfoTile second = wall(h, actor.player, new BlockPos(4, 2, 2));
            second.joinAdjacent();
            actor.player.setPos(second.getBlockPos().getCenter());
            var group = PanelGroup.of(second);
            h.assertTrue(group.members().size() == 2 && group.root() == first
                            && OperationsAccess.requireView(actor.player, second) == f.manager,
                    "same owner, role and binding form one current operations wall");
            CompoundTag rootTag = first.getUpdateTag(h.getLevel().registryAccess());
            CompoundTag memberTag = second.getUpdateTag(h.getLevel().registryAccess());
            h.assertTrue(rootTag.contains("operations_snapshot") && !memberTag.contains("operations_snapshot"),
                    "only the actual public rectangle root publishes operations rows");
            Map<BlockPos, InfoTile> publicTiles = new HashMap<>();
            publicTiles.put(first.getBlockPos(), copy(h, first, rootTag));
            publicTiles.put(second.getBlockPos(), copy(h, second, memberTag));
            var reconstructed = PanelGroup.of(publicTiles.get(second.getBlockPos()), publicTiles::get, true);
            h.assertTrue(reconstructed.members().size() == 2
                            && reconstructed.signature().equals(group.signature()),
                    "public tags reconstruct the same role-specific rectangle without private owner metadata");
            var request = args(f, second);
            second.terminalRole = TerminalRoleSettings.LEGACY;
            h.assertTrue(PanelGroup.of(first).members().size() == 1 && PanelGroup.of(second).members().size() == 1,
                    "adjacent audience and operations tiles never share a surface");
            rejects(h, "not_operations", () -> OperationsAccess.requireControl(actor.player, second, request));
            second.terminalRole = TerminalRoleSettings.OPERATIONS;
            second.owner = UUID.randomUUID();
            h.assertTrue(PanelGroup.of(first).members().size() == 1 && PanelGroup.of(second).members().size() == 1,
                    "owner change immediately partitions current operations membership");
            rejects(h, "stale_binding", () -> OperationsAccess.requireView(actor.player, second));
        }
        h.succeed();
    }

    private static InfoTile wall(GameTestHelper h, ServerPlayer actor, BlockPos relative) {
        h.setBlock(relative.south(), Blocks.STONE);
        h.setBlock(relative, CinemaManager.WALL_INFO.get().defaultBlockState().setValue(InfoBlock.FACING, Direction.NORTH));
        InfoTile tile = h.getBlockEntity(relative);
        tile.initializePlacement(actor.getUUID());
        return tile;
    }
}
