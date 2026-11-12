package local.cinemamanager.qa;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.InfoTile;
import local.cinemamanager.panels.PanelGroup;
import local.cinemamanager.panels.PanelNetwork;
import local.cinemamanager.panels.PanelScope;
import local.cinemamanager.panels.PanelSettings;
import local.cinemamanager.panels.PosterGrant;
import local.cinemamanager.panels.PosterPolicy;
import local.cinemamanager.panels.PublicPanelSnapshot;
import local.cinemamanager.policy.CinemaPermissions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Loaded-world permission regressions. No media is opened or fetched by these tests. */
@GameTestHolder("mycinema")
@PrefixGameTestTemplate(false)
public final class PosterGrantGameTests {
    private static final String INPUT = "https://POSTER-GRANT-QA.invalid/poster.png?sig=one%2Ftwo&v=1";
    private static final String SOURCE = "https://poster-grant-qa.invalid/poster.png?sig=one%2Ftwo&v=1";
    private static final String CHANGED_SOURCE = "https://poster-grant-qa.invalid/poster.png?sig=one%2Ftwo&v=2";
    private static final String WHITELISTED = "https://i.imgur.com/poster-grant-qa.png";
    private static final String RESOURCE = "resource:mycinema:textures/block/info.png";

    private static ServerPlayer actor(GameTestHelper h) {
        // FakePlayer supplies an inert network connection, but inherits real profile permissions.
        ServerPlayer actor = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "PosterGrantQA"));
        actor.setGameMode(GameType.SURVIVAL);
        actor.setPos(h.absolutePos(new BlockPos(3, 2, 2)).getCenter());
        actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CinemaManager.INFO_ITEM.get(), 16));
        h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayer(actor.getUUID()) == null,
                "test author is absent from the online player list");
        return actor;
    }

    /** Scope the real OP entry to this isolated test server, restoring it even after assertion failures. */
    private static final class TemporaryOp implements AutoCloseable {
        private final ServerOpList ops;
        private final GameProfile profile;
        private final ServerOpListEntry previous;

        TemporaryOp(GameTestHelper h, ServerPlayer actor) {
            h.assertTrue(Boolean.getBoolean("mycinema.qa") && h.getLevel().getServer() instanceof GameTestServer,
                    "OP fixtures are restricted to the opt-in isolated GameTest server");
            ops = h.getLevel().getServer().getPlayerList().getOps();
            profile = actor.getGameProfile();
            previous = ops.get(profile);
            h.assertFalse(actor.hasPermissions(4), "fresh fixture has no implicit OP4 authority");
            h.assertFalse(CinemaPermissions.isWhitelisted(SOURCE), "fixture URL requires a whitelist bypass");
            h.assertTrue(CinemaPermissions.isWhitelisted(WHITELISTED), "legacy fixture URL is whitelisted");
            h.assertFalse(PosterPolicy.maySave(actor, SOURCE), "ordinary actor cannot save the bypass fixture");
        }

        void grant() { ops.add(new ServerOpListEntry(profile, 4, false)); }
        void revoke() { ops.remove(profile); }

        @Override public void close() {
            if (previous == null) ops.remove(profile);
            else ops.add(previous);
        }
    }

    private static void save(ServerPlayer actor, InfoTile tile, String source) {
        tile.configure(actor, PanelGroup.of(tile).signature(), PanelSettings.DEFAULT.withPoster(source));
    }

    private static CompoundTag disk(GameTestHelper h, InfoTile tile) {
        return tile.saveWithFullMetadata(h.getLevel().registryAccess());
    }

    private static InfoTile reload(GameTestHelper h, InfoTile old, CompoundTag saved) {
        InfoTile loaded = new InfoTile(old.getBlockPos(), old.getBlockState());
        loaded.loadWithComponents(saved, h.getLevel().registryAccess());
        h.getLevel().removeBlockEntity(old.getBlockPos());
        h.getLevel().setBlockEntity(loaded);
        h.assertTrue(h.getLevel().getBlockEntity(loaded.getBlockPos()) == loaded && loaded.getLevel() == h.getLevel(),
                "normal NBT reload installs a real live block entity");
        return loaded;
    }

    private static void published(GameTestHelper h, InfoTile tile, String expected, String reason) {
        CompoundTag update = tile.getUpdateTag(h.getLevel().registryAccess());
        h.assertTrue(tile.approvedPoster().equals(expected)
                        && PanelNetwork.guestData(tile).settings().poster().equals(expected)
                        && update.getString("published_poster").equals(expected)
                        && update.getCompound("panel_settings").getString("poster").equals(expected),
                reason + ": live approval, guest response and both update-tag references agree");
    }

    private static PosterGrant grant(GameTestHelper h, InfoTile tile, ServerPlayer expectedAuthor, String source) {
        CompoundTag saved = disk(h, tile);
        h.assertTrue(saved.contains("poster_grant"), "disk NBT preserves a server-private grant");
        PosterGrant grant = PosterGrant.read(saved.getCompound("poster_grant"));
        h.assertTrue(grant != null && grant.author().equals(expectedAuthor.getUUID())
                        && grant.authorName().equals(expectedAuthor.getGameProfile().getName())
                        && grant.panelIdentity().equals(tile.identity) && grant.panelOwner().equals(tile.owner)
                        && grant.source().equals(source),
                "saved grant binds the actual author, this panel identity, owner and exact normalized URL");
        return grant;
    }

    private static void redacted(GameTestHelper h, InfoTile tile, ServerPlayer author) {
        CompoundTag update = tile.getUpdateTag(h.getLevel().registryAccess());
        for (String privateKey : List.of("poster_grant", "author", "author_name", "owner", "manager", "manager_id")) {
            h.assertFalse(update.contains(privateKey), "public update excludes " + privateKey);
        }
        String guest = PanelNetwork.JSON.toJson(PanelNetwork.guestData(tile));
        h.assertFalse(guest.contains("poster_grant") || guest.contains("authorName")
                        || guest.contains(author.getUUID().toString()) || guest.contains(author.getGameProfile().getName()),
                "guest JSON contains no grant or author identity");
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void poster_op_bypass_survives_offline_reload_and_current_revocation(GameTestHelper h) {
        ServerPlayer author = actor(h);
        try (TemporaryOp op = new TemporaryOp(h, author)) {
            op.grant();
            h.assertTrue(author.hasPermissions(4) && PosterPolicy.maySave(author, SOURCE),
                    "real current OP4 permission authorizes the nonwhitelisted save");
            InfoTile panel = PanelGameTests.place(h, author, new BlockPos(2, 1, 2));
            save(author, panel, INPUT);
            h.assertTrue(panel.panel.poster().equals(SOURCE), "save retains exact normalized signed URL bytes");
            grant(h, panel, author, SOURCE);
            published(h, panel, SOURCE, "OP4 bypass publishes immediately");
            redacted(h, panel, author);

            panel = reload(h, panel, disk(h, panel));
            h.assertTrue(h.getLevel().getServer().getPlayerList().getPlayer(author.getUUID()) == null,
                    "restored grant is evaluated without an online author");
            published(h, panel, SOURCE, "ordinary NBT reload rechecks the offline author's current OP4 entry");

            op.revoke();
            h.assertFalse(author.hasPermissions(4) || PosterPolicy.maySave(author, SOURCE), "OP4 is actually revoked");
            published(h, panel, "", "revocation hides every public reference immediately, before refresh");
            panel.refreshNow();
            h.assertTrue(panel.publishedPoster.isEmpty(), "refresh clears the cached public poster after revocation");
            grant(h, panel, author, SOURCE);

            op.grant();
            published(h, panel, SOURCE, "regrant restores the same saved source without rewriting its authorship");
        }
        h.assertFalse(author.hasPermissions(4), "temporary OP entry is removed after the test");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void poster_grant_binds_owner_identity_and_exact_source(GameTestHelper h) {
        ServerPlayer author = actor(h);
        try (TemporaryOp op = new TemporaryOp(h, author)) {
            op.grant();
            InfoTile panel = PanelGameTests.place(h, author, new BlockPos(2, 1, 2));
            save(author, panel, SOURCE);
            CompoundTag original = disk(h, panel);
            published(h, panel, SOURCE, "control grant is usable");

            UUID owner = panel.owner;
            panel.owner = UUID.randomUUID();
            published(h, panel, "", "changed ownership cannot reuse old authority");
            panel.owner = owner;
            published(h, panel, SOURCE, "restoring the bound owner restores the control grant");

            UUID identity = panel.identity;
            panel.identity = UUID.randomUUID();
            published(h, panel, "", "replacement panel identity cannot reuse old authority");
            panel.identity = identity;
            published(h, panel, SOURCE, "restoring the original identity restores the control grant");

            panel.panel = panel.panel.withPoster(CHANGED_SOURCE);
            published(h, panel, "", "even a query-byte change requires a new authorized save");
            panel.panel = panel.panel.withPoster(SOURCE);
            published(h, panel, SOURCE, "restoring the exact source restores the control grant");

            InfoTile detached = new InfoTile(panel.getBlockPos(), panel.getBlockState());
            detached.loadWithComponents(original, h.getLevel().registryAccess());
            detached.setLevel(h.getLevel());
            h.assertTrue(detached.approvedPoster().isEmpty(), "a detached copied tile cannot exercise the installed tile's grant");

            CompoundTag replaced = original.copy();
            replaced.putUUID("identity", UUID.randomUUID());
            panel = reload(h, panel, replaced);
            published(h, panel, "", "mismatched identity also fails after actual persisted-NBT reload");
        }
        h.succeed();
    }

    private static JsonObject forgedConfiguration(InfoTile tile, ServerPlayer forgedAuthor, String requestedSource) {
        JsonObject json = PanelNetwork.JSON.toJsonTree(new PanelNetwork.Configuration(
                PanelSettings.DEFAULT.withPoster(requestedSource), PanelScope.HALL)).getAsJsonObject();
        PosterGrant invented = new PosterGrant(forgedAuthor.getUUID(), forgedAuthor.getGameProfile().getName(),
                tile.identity, tile.owner, SOURCE);
        json.add("posterGrant", PanelNetwork.JSON.toJsonTree(invented));
        json.add("poster_grant", PanelNetwork.JSON.toJsonTree(invented));
        json.addProperty("published_poster", SOURCE);
        json.addProperty("approved", true);
        json.getAsJsonObject("settings").add("poster_grant", PanelNetwork.JSON.toJsonTree(invented));
        return json;
    }

    private static void sendSave(ServerPlayer actor, InfoTile tile, JsonObject json) {
        PanelNetwork.apply(actor, new PanelNetwork.Edit(tile.getBlockPos(), tile.identity.toString(),
                PanelGroup.of(tile).signature(), "save", json.toString(), UUID.randomUUID().toString()));
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void poster_legacy_nbt_and_forged_client_grants_fail_closed(GameTestHelper h) {
        ServerPlayer author = actor(h);
        try (TemporaryOp op = new TemporaryOp(h, author)) {
            op.grant();
            InfoTile legacy = PanelGameTests.place(h, author, new BlockPos(2, 1, 2));
            save(author, legacy, SOURCE);
            CompoundTag oldSave = disk(h, legacy);
            oldSave.remove("poster_grant");
            // Public-looking fields are not a substitute for server-private authorship on load.
            oldSave.putString("published_poster", SOURCE);
            oldSave.put("guest_snapshot", PublicPanelSnapshot.EMPTY.save());
            legacy = reload(h, legacy, oldSave);
            published(h, legacy, "", "legacy nonwhitelisted source lacks permission evidence even while its owner is OP");
            h.assertTrue(legacy.panel.poster().equals(SOURCE), "legacy source remains privately recoverable by its owner");
            for (String preserved : List.of(WHITELISTED, RESOURCE, "")) {
                CompoundTag saved = oldSave.copy();
                saved.put("panel_settings", PanelSettings.DEFAULT.withPoster(preserved).save());
                legacy = reload(h, legacy, saved);
                published(h, legacy, preserved, "legacy whitelist/resource/empty reference remains compatible");
            }
            h.assertFalse(CinemaPermissions.canSavePosterOffline(h.getLevel().getServer(),
                            new GameProfile(UUID.randomUUID(), "SrRapero720"), SOURCE),
                    "creator-name-only profile cannot invent offline whitelist-bypass authority");

            ServerPlayer ordinary = actor(h);
            InfoTile allowed = PanelGameTests.place(h, ordinary, new BlockPos(4, 1, 2));
            h.assertTrue(PosterPolicy.maySave(ordinary, WHITELISTED), "ordinary owner's whitelist control save is allowed");
            sendSave(ordinary, allowed, forgedConfiguration(allowed, author, WHITELISTED));
            h.assertTrue(allowed.panelRevision == 1 && allowed.panel.poster().equals(WHITELISTED),
                    "real configuration packet reaches a successful permitted save");
            grant(h, allowed, ordinary, WHITELISTED);
            allowed.panel = allowed.panel.withPoster(SOURCE);
            published(h, allowed, "", "successful client save cannot inject a different OP author's grant");

            // A distinct actor avoids the production packet rate limiter masking the denied-save assertion.
            ServerPlayer deniedActor = actor(h);
            InfoTile denied = PanelGameTests.place(h, deniedActor, new BlockPos(6, 1, 2));
            sendSave(deniedActor, denied, forgedConfiguration(denied, author, SOURCE));
            h.assertTrue(denied.panelRevision == 0 && denied.panel.equals(PanelSettings.DEFAULT)
                            && !disk(h, denied).contains("poster_grant"),
                    "forged nonwhitelisted configuration changes neither state, revision nor private grant");
            published(h, denied, "", "forged grant fields cannot turn a denied save into a public poster");
        }
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void poster_column_extension_reload_and_root_replacement_preserve_authority(GameTestHelper h) {
        ServerPlayer author = actor(h);
        try (TemporaryOp op = new TemporaryOp(h, author)) {
            op.grant();
            InfoTile lower = PanelGameTests.place(h, author, new BlockPos(2, 1, 2));
            save(author, lower, SOURCE);
            InfoTile upper = PanelGameTests.place(h, author, new BlockPos(2, 2, 2));
            h.assertTrue(PanelGroup.of(lower).height() == 2, "same-owner placement extends the authorized column");
            PosterGrant lowerGrant = grant(h, lower, author, SOURCE);
            PosterGrant upperGrant = grant(h, upper, author, SOURCE);
            h.assertFalse(lowerGrant.panelIdentity().equals(upperGrant.panelIdentity()),
                    "extension creates a grant bound to each distinct member identity");
            published(h, upper, SOURCE, "valid extension retains the original author and bypass");

            CompoundTag lowerSave = disk(h, lower), upperSave = disk(h, upper);
            lower = reload(h, lower, lowerSave);
            upper = reload(h, upper, upperSave);
            h.assertTrue(PanelGroup.of(upper).height() == 2, "normal NBT reload reconstructs the full column");
            published(h, lower, SOURCE, "both restored grants authorize the reconstructed column");
            redacted(h, lower, author);
            redacted(h, upper, author);

            op.revoke();
            InfoTile top = PanelGameTests.place(h, author, new BlockPos(2, 3, 2));
            h.assertTrue(PanelGroup.of(lower).height() == 3, "revoked author can still place an owned physical segment");
            h.assertFalse(disk(h, top).contains("poster_grant"), "placement while revoked cannot mint a bypass grant");
            op.grant();
            published(h, lower, "", "one ungranted member keeps the entire column private after OP restoration");
            save(author, lower, SOURCE);
            for (InfoTile member : PanelGroup.of(lower).members()) {
                grant(h, member, author, SOURCE);
                published(h, member, SOURCE, "explicit authorized save renews every column member");
                redacted(h, member, author);
            }

            h.setBlock(new BlockPos(2, 1, 2), Blocks.AIR);
            upper.refreshNow();
            h.assertTrue(PanelGroup.of(upper).root() == upper && PanelGroup.of(upper).height() == 2,
                    "remaining segment becomes the real column root after removal");
            published(h, upper, SOURCE, "root replacement preserves its own identity-bound grant");
            redacted(h, upper, author);
        }
        h.succeed();
    }

    private static void safeItemCopy(GameTestHelper h, InfoTile panel, String expectedSource) {
        ItemStack picked = new ItemStack(CinemaManager.INFO_ITEM.get());
        panel.saveToItem(picked, h.getLevel().registryAccess());
        CustomData component = picked.get(DataComponents.BLOCK_ENTITY_DATA);
        h.assertTrue(component != null, "actual vanilla saveToItem retains public panel customization");
        CompoundTag tag = component.copyTag();
        for (String key : tag.getAllKeys()) {
            h.assertTrue(List.of("id", "panel_settings", "panel_scope").contains(key),
                    "creative item exports only explicitly public fields, found " + key);
        }
        PanelSettings settings = PanelSettings.read(tag.getCompound("panel_settings"));
        h.assertTrue(settings.poster().equals(expectedSource) && settings.next().isEmpty() && settings.footer().isEmpty(),
                "creative item copy includes only currently approved poster and public text");
        h.assertTrue(settings.layout() == panel.panel.layout() && settings.foreground() == panel.panel.foreground()
                        && settings.title().equals(panel.panel.title()),
                "creative item redaction preserves harmless style and public hall title");
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void poster_creative_item_nbt_cannot_transfer_grants_or_private_bindings(GameTestHelper h) {
        ServerPlayer author = actor(h);
        try (TemporaryOp op = new TemporaryOp(h, author)) {
            op.grant();
            InfoTile source = PanelGameTests.place(h, author, new BlockPos(2, 1, 2));
            PanelSettings styled = new PanelSettings(PanelSettings.Layout.POSTER_TOP, "Public hall", "Public title", "",
                    "PRIVATE_NEXT", "PRIVATE_FOOTER", SOURCE, 0x123456, 0x010203, 0xabcdef, 110, PanelSettings.ALL);
            source.configure(author, PanelGroup.of(source).signature(), styled);
            source.manager = h.absolutePos(new BlockPos(7, 1, 5));
            source.managerIdentity = UUID.randomUUID();
            source.hallId = "PRIVATE_HALL_BINDING";
            safeItemCopy(h, source, SOURCE);

            ServerPlayer placer = actor(h);
            placer.setGameMode(GameType.CREATIVE);
            h.assertFalse(placer.hasPermissions(2) || PosterPolicy.maySave(placer, SOURCE),
                    "ordinary creative placer has neither OP NBT authority nor poster bypass");

            // Forge a matching ownership/identity/source proof with a genuinely current OP author.
            // Strip coordinates, as a client-provided creative inventory item does.
            CompoundTag forged = disk(h, source);
            UUID claimedIdentity = UUID.randomUUID();
            forged.putUUID("identity", claimedIdentity);
            forged.putUUID("owner", placer.getUUID());
            forged.put("poster_grant", new PosterGrant(author.getUUID(), author.getGameProfile().getName(),
                    claimedIdentity, placer.getUUID(), SOURCE).save());
            for (String axis : List.of("x", "y", "z")) forged.remove(axis);

            ItemStack wallItem = new ItemStack(CinemaManager.INFO_ITEM.get());
            wallItem.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(forged.copy()));
            placer.setItemInHand(InteractionHand.MAIN_HAND, wallItem);
            InfoTile wall = PanelGameTests.place(h, placer, new BlockPos(4, 1, 2));

            ItemStack cubeItem = new ItemStack(CinemaManager.INFO_ITEM.get());
            cubeItem.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(forged.copy()));
            placer.setItemInHand(InteractionHand.MAIN_HAND, cubeItem);
            BlockPos cubePos = new BlockPos(6, 1, 2), support = cubePos.below();
            h.setBlock(support, Blocks.STONE);
            var context = new UseOnContext(placer, InteractionHand.MAIN_HAND,
                    new BlockHitResult(h.absolutePos(support).getCenter().add(0, .5, 0),
                            Direction.UP, h.absolutePos(support), false));
            h.assertTrue(((BlockItem) CinemaManager.INFO_ITEM.get()).place(new BlockPlaceContext(context)).consumesAction(),
                    "real top-face placement accepts the forged creative item as a physical cube");
            h.assertTrue(h.getBlockState(cubePos).is(CinemaManager.INFO.get()), "top-face item placement uses legacy cube state");
            InfoTile cube = h.getBlockEntity(cubePos);

            for (InfoTile placed : List.of(wall, cube)) {
                h.assertTrue(placed.owner.equals(placer.getUUID()) && !placed.identity.equals(claimedIdentity),
                        "real block placement assigns the actual owner and a fresh panel identity");
                h.assertFalse(disk(h, placed).contains("poster_grant"), "creative item NBT cannot install server-private authorship");
                published(h, placed, "", "ordinary creative item placement cannot publish a forged bypass poster");
                redacted(h, placed, author);
            }

            op.revoke();
            safeItemCopy(h, source, "");
        }
        h.succeed();
    }
}
