package local.cinemamanager.qa;

import com.mojang.authlib.GameProfile;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.InfoBlock;
import local.cinemamanager.InfoTile;
import local.cinemamanager.panels.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/** Real item placement, ownership, saved grants and public-tag reconstruction in all wall planes. */
@GameTestHolder("mycinema")
@PrefixGameTestTemplate(false)
public final class PanelPlaneGameTests {
    private static ServerPlayer actor(GameTestHelper h) {
        ServerPlayer actor = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "PanelPlaneQA"));
        actor.setGameMode(GameType.SURVIVAL);
        actor.setPos(h.absolutePos(new BlockPos(5, 2, 5)).getCenter());
        actor.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CinemaManager.INFO_ITEM.get(), 64));
        return actor;
    }

    private static InfoTile place(GameTestHelper h, ServerPlayer actor, BlockPos wall, Direction facing) {
        BlockPos support = wall.relative(facing.getOpposite());
        h.setBlock(support, Blocks.STONE);
        BlockPos absoluteSupport = h.absolutePos(support);
        var hit = new BlockHitResult(absoluteSupport.getCenter().add(facing.getStepX() * .5, 0, facing.getStepZ() * .5),
                facing, absoluteSupport, false);
        var context = new BlockPlaceContext(new UseOnContext(actor, InteractionHand.MAIN_HAND, hit));
        h.assertTrue(((BlockItem) CinemaManager.INFO_ITEM.get()).place(context).consumesAction(), "real wall item placement succeeds");
        h.assertTrue(h.getBlockState(wall).is(CinemaManager.WALL_INFO.get())
                && h.getBlockState(wall).getValue(InfoBlock.FACING) == facing, "placed wall keeps exact clicked outward facing");
        return h.getBlockEntity(wall);
    }

    private static InfoTile reload(GameTestHelper h, InfoTile old) {
        CompoundTag saved = old.saveWithFullMetadata(h.getLevel().registryAccess());
        InfoTile restored = new InfoTile(old.getBlockPos(), old.getBlockState());
        restored.loadWithComponents(saved, h.getLevel().registryAccess());
        h.getLevel().removeBlockEntity(old.getBlockPos());
        h.getLevel().setBlockEntity(restored);
        return restored;
    }

    private static Map<BlockPos, InfoTile> publicCopies(GameTestHelper h, Collection<InfoTile> members) {
        Map<BlockPos, InfoTile> copies = new HashMap<>();
        for (InfoTile member : members) {
            InfoTile copy = new InfoTile(member.getBlockPos(), member.getBlockState());
            copy.loadWithComponents(member.getUpdateTag(h.getLevel().registryAccess()), h.getLevel().registryAccess());
            h.assertTrue(copy.owner == null && copy.manager == null && copy.managerIdentity == null && copy.hallId.isEmpty(),
                    "client surface needs no private owner or binding metadata");
            copies.put(copy.getBlockPos(), copy);
        }
        return copies;
    }

    private static void samePublicSurfaces(GameTestHelper h, Collection<InfoTile> members) {
        Map<BlockPos, InfoTile> publicTiles = publicCopies(h, members);
        for (InfoTile member : members) {
            PanelGroup server = PanelGroup.of(member);
            PanelGroup client = PanelGroup.of(publicTiles.get(member.getBlockPos()), publicTiles::get, true);
            h.assertTrue(server.signature().equals(client.signature()) && server.width() == client.width()
                            && server.height() == client.height() && server.root().getBlockPos().equals(client.root().getBlockPos()),
                    "opaque public tags reconstruct the exact same rectangle, root and current membership signature");
        }
    }

    private static void rectangleLifecycle(GameTestHelper h, Direction facing) {
        ServerPlayer owner = actor(h);
        Direction right = facing.getCounterClockWise();
        BlockPos rootPos = new BlockPos(5, 1, 5);
        List<InfoTile> members = new ArrayList<>();
        // Grow down, left, right, and up from an initial upper-middle tile.
        for (int[] point : List.of(new int[]{1, 1}, new int[]{1, 0}, new int[]{0, 0},
                new int[]{2, 0}, new int[]{0, 1}, new int[]{2, 1})) {
            members.add(place(h, owner, rootPos.relative(right, point[0]).above(point[1]), facing));
            if (members.size() == 5) {
                PanelGroup square = PanelGroup.of(members.getLast());
                h.assertTrue(square.width() == 2 && square.height() == 2 && square.members().size() == 4,
                        "the completed 2x2 square merges before the final adjacent column is filled");
            }
        }
        for (InfoTile member : members) {
            PanelGroup group = PanelGroup.of(member);
            h.assertTrue(group.width() == 3 && group.height() == 2 && group.members().size() == 6
                            && group.root().getBlockPos().equals(h.absolutePos(rootPos)) && PanelGroup.right(member) == right,
                    "every clicked tile agrees on one 3x2 surface and bottom-left renderer root for " + facing);
        }
        Map<BlockPos, UUID> savedGroups = new HashMap<>();
        members.forEach(tile -> savedGroups.put(tile.getBlockPos(), tile.panelGroup));
        String signature = PanelGroup.of(members.getFirst()).signature();
        members.replaceAll(tile -> reload(h, tile));
        for (InfoTile member : members)
            h.assertTrue(member.panelGroup.equals(savedGroups.get(member.getBlockPos()))
                    && PanelGroup.of(member).signature().equals(signature), "normal world reload preserves saved UUIDs and surface membership");
        samePublicSurfaces(h, members);

        h.setBlock(rootPos.relative(facing.getOpposite()), Blocks.AIR);
        h.runAfterDelay(2, () -> {
            h.assertTrue(h.getBlockState(rootPos).isAir(), "breaking real wall support removes the old bottom-left root");
            List<InfoTile> remaining = members.stream().filter(tile -> !tile.getBlockPos().equals(h.absolutePos(rootPos))).toList();
            Set<BlockPos> covered = new HashSet<>();
            Set<String> surfaces = new HashSet<>();
            for (InfoTile member : remaining) {
                PanelGroup group = PanelGroup.of(member);
                h.assertFalse(group.signature().equals(signature), "old complete-surface editor token becomes stale after root removal");
                if (surfaces.add(group.signature())) for (InfoTile part : group.members())
                    h.assertTrue(covered.add(part.getBlockPos()), "split rectangles do not overlap");
            }
            h.assertTrue(covered.size() == 5 && !covered.contains(h.absolutePos(rootPos)) && surfaces.size() == 2,
                    "five remaining physical tiles split into filled rectangles without drawing over the missing corner");
            samePublicSurfaces(h, remaining);
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void panel_plane_north_item_growth_reload_and_break(GameTestHelper h) { rectangleLifecycle(h, Direction.NORTH); }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void panel_plane_south_item_growth_reload_and_break(GameTestHelper h) { rectangleLifecycle(h, Direction.SOUTH); }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void panel_plane_east_item_growth_reload_and_break(GameTestHelper h) { rectangleLifecycle(h, Direction.EAST); }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void panel_plane_west_item_growth_reload_and_break(GameTestHelper h) { rectangleLifecycle(h, Direction.WEST); }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void panel_plane_separate_ids_merge_without_rewriting_grants(GameTestHelper h) {
        ServerPlayer owner = actor(h);
        InfoTile left = place(h, owner, new BlockPos(6, 1, 3), Direction.NORTH);
        InfoTile right = place(h, owner, new BlockPos(4, 1, 3), Direction.NORTH);
        String source = "https://i.imgur.com/panel-plane-qa.png";
        for (InfoTile tile : List.of(left, right)) tile.configure(owner, PanelGroup.of(tile).signature(), PanelSettings.DEFAULT.withPoster(source));
        UUID leftGroup = left.panelGroup, rightGroup = right.panelGroup;
        CompoundTag leftGrant = left.saveWithFullMetadata(h.getLevel().registryAccess()).getCompound("poster_grant").copy();
        CompoundTag rightGrant = right.saveWithFullMetadata(h.getLevel().registryAccess()).getCompound("poster_grant").copy();
        h.assertFalse(leftGroup.equals(rightGroup), "separate panels initially have independent persistent group IDs");
        h.assertTrue(leftGrant.hasUUID("panel") && rightGrant.hasUUID("panel"), "each original panel has its own saved poster authorship grant");
        String stale = PanelGroup.of(left).signature();
        InfoTile bridge = place(h, owner, new BlockPos(5, 1, 3), Direction.NORTH);
        PanelGroup group = PanelGroup.of(bridge);
        h.assertTrue(group.width() == 3 && group.height() == 1 && group.members().size() == 3,
                "a real horizontal bridge merges exact compatible neighbors despite independent saved IDs");
        h.assertTrue(left.panelGroup.equals(leftGroup) && right.panelGroup.equals(rightGroup), "merge does not rewrite either original disk group UUID");
        h.assertTrue(leftGrant.equals(left.saveWithFullMetadata(h.getLevel().registryAccess()).getCompound("poster_grant"))
                        && rightGrant.equals(right.saveWithFullMetadata(h.getLevel().registryAccess()).getCompound("poster_grant")),
                "merge retains the exact original identity-bound grants");
        PosterGrant newGrant = PosterGrant.read(bridge.saveWithFullMetadata(h.getLevel().registryAccess()).getCompound("poster_grant"));
        h.assertTrue(newGrant != null && newGrant.panelIdentity().equals(bridge.identity) && newGrant.author().equals(owner.getUUID()),
                "only the new physical tile receives an extension bound to its own identity");
        PanelGameTests.rejects(h, () -> left.configure(owner, stale, PanelSettings.DEFAULT), "pre-merge editor token cannot overwrite the new surface");
        samePublicSurfaces(h, group.members());
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void panel_plane_conflicting_settings_owners_bindings_and_scope_stay_separate(GameTestHelper h) {
        ServerPlayer owner = actor(h), stranger = actor(h);
        InfoTile left = place(h, owner, new BlockPos(6, 1, 3), Direction.NORTH);
        InfoTile right = place(h, owner, new BlockPos(4, 1, 3), Direction.NORTH);
        PanelSettings custom = PanelSettings.DEFAULT.withPoster("resource:minecraft:textures/block/stone.png");
        right.configure(owner, PanelGroup.of(right).signature(), custom);
        UUID rightGroup = right.panelGroup;
        InfoTile bridge = place(h, owner, new BlockPos(5, 1, 3), Direction.NORTH);
        h.assertTrue(PanelGroup.of(left).width() == 2 && PanelGroup.of(right).width() == 1
                        && bridge.panel.equals(left.panel) && right.panel.equals(custom) && right.panelGroup.equals(rightGroup),
                "ambiguous bridging selects a deterministic adjacent source and preserves the conflicting neighbor");
        InfoTile foreign = place(h, stranger, new BlockPos(3, 1, 3), Direction.NORTH);
        h.assertTrue(PanelGroup.of(foreign).members().size() == 1 && foreign.owner.equals(stranger.getUUID()),
                "different owner stays independent even with physical adjacency");
        PanelGameTests.rejects(h, () -> left.configure(stranger, PanelGroup.of(left).signature(), custom), "foreign actor cannot edit another owner's two-dimensional group");

        InfoTile probe = new InfoTile(left.getBlockPos(), left.getBlockState());
        probe.owner = left.owner;
        probe.panel = left.panel;
        probe.scope = left.scope;
        h.assertTrue(PanelGroup.compatible(left, probe), "matching data is compatible independently of disk UUID");
        probe.manager = new BlockPos(12, 4, 12);
        h.assertFalse(PanelGroup.compatible(left, probe), "different controller coordinates prevent merging");
        probe.manager = left.manager;
        probe.managerIdentity = UUID.randomUUID();
        h.assertFalse(PanelGroup.compatible(left, probe), "different controller identity prevents merging");
        probe.managerIdentity = left.managerIdentity;
        probe.hallId = "other_hall";
        h.assertFalse(PanelGroup.compatible(left, probe), "different bound hall prevents merging");
        probe.hallId = left.hallId;
        probe.scope = left.scope == PanelScope.HALL ? PanelScope.LOBBY : PanelScope.HALL;
        h.assertFalse(PanelGroup.compatible(left, probe), "different display scope prevents merging");
        probe.scope = left.scope;
        probe.setBlockState(probe.getBlockState().setValue(InfoBlock.FACING, Direction.SOUTH));
        h.assertFalse(PanelGroup.compatible(left, probe), "opposite-facing panel never joins the same plane");
        samePublicSurfaces(h, List.of(left, bridge, right, foreign));
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void panel_plane_partial_loaded_lookup_and_hole_are_exact(GameTestHelper h) {
        ServerPlayer owner = actor(h);
        Map<BlockPos, InfoTile> tiles = new HashMap<>();
        for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++) {
            InfoTile tile = place(h, owner, new BlockPos(6 - x, 1 + y, 3), Direction.NORTH);
            tiles.put(tile.getBlockPos(), tile);
        }
        InfoTile lowerLeft = tiles.get(h.absolutePos(new BlockPos(6, 1, 3)));
        String full = PanelGroup.of(lowerLeft).signature();
        int missingX = h.absolutePos(new BlockPos(5, 1, 3)).getX();
        for (InfoTile tile : tiles.values()) if (tile.getBlockPos().getX() != missingX) {
            PanelGroup partial = PanelGroup.of(tile, pos -> pos.getX() == missingX ? null : tiles.get(pos), false);
            h.assertTrue(partial.width() == 1 && partial.height() == 2 && !partial.signature().equals(full),
                    "unavailable middle strip splits loaded sides and invalidates complete-surface tokens");
            h.assertTrue(partial.members().stream().allMatch(member -> member.getBlockPos().getX() == tile.getBlockPos().getX()),
                    "partial discovery never reaches an unavailable strip or disconnected opposite side");
        }
        BlockPos far = new BlockPos(100000, 64, 100000);
        h.assertFalse(h.getLevel().hasChunkAt(far), "distant probe starts unloaded");
        h.assertTrue(PanelGroup.loaded(h.getLevel(), far) == null, "production lookup refuses unloaded chunk");
        h.assertFalse(h.getLevel().hasChunkAt(far), "lookup creates no chunk load or ticket");
        h.setBlock(new BlockPos(5, 2, 3), Blocks.AIR);
        List<InfoTile> remaining = tiles.values().stream().filter(tile -> !tile.getBlockPos().equals(h.absolutePos(new BlockPos(5, 2, 3)))).toList();
        samePublicSurfaces(h, remaining);
        for (InfoTile tile : remaining) h.assertTrue(PanelGroup.of(tile).members().stream()
                        .noneMatch(member -> member.getBlockPos().equals(h.absolutePos(new BlockPos(5, 2, 3)))),
                "a real hole is never counted or drawn as part of a rectangle");
        h.succeed();
    }
}
