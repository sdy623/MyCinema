package local.cinemamanager.seats.fractional;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** Native regressions for exit support hazards and complete passenger AABBs. Run only in a QA save. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class FractionalSeatSafetyGameTests {
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void fractional_exit_rejects_hazardous_support_and_floor_overlap(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos pos = helper.absolutePos(new BlockPos(5, 3, 5));
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, 3, 2)))
            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
            new GameProfile(UUID.randomUUID(), "FractionalSafetyQA"), ClientInformation.createDefault());
        Vec3 feet = new Vec3(pos.getX() + .5, pos.getY(), pos.getZ() + .5); player.setPos(feet);
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        helper.assertTrue(FractionalSeatExitSafety.safeStanding(level, player, feet), "ordinary solid exit support is accepted");
        helper.assertTrue(FractionalSeatExitSafety.assessStanding(level, player, feet).code().equals("EXIT_SUPPORT_OK"), "ordinary support reason is explicit");
        level.setBlockAndUpdate(pos.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
        helper.assertFalse(FractionalSeatExitSafety.safeStanding(level, player, feet), "magma immediately below feet is refused even though the body AABB is clear");
        var magma = FractionalSeatExitSafety.assessStanding(level, player, feet);
        helper.assertTrue(magma.code().equals("EXIT_SUPPORT_HAZARD") && magma.block().equals(pos.below()), "magma failure names downward support and exact block");
        level.setBlockAndUpdate(pos.below(), FractionalSeatHost.ANDESITE_BOTTOM_SLAB);
        Vec3 halfFeet = feet.add(0, -.5, 0);
        helper.assertTrue(FractionalSeatExitSafety.safeStanding(level, player, halfFeet), "retained dry half-slab top remains a valid exit support");
        level.setBlockAndUpdate(pos.below(), FractionalSeatHost.ANDESITE_BOTTOM_SLAB.setValue(SlabBlock.WATERLOGGED, true));
        helper.assertFalse(FractionalSeatExitSafety.safeStanding(level, player, halfFeet), "wet support is refused");
        helper.assertTrue(FractionalSeatExitSafety.assessStanding(level, player, halfFeet).code().equals("EXIT_BODY_HAZARD"), "water above slab intersects exit body");
        level.setBlockAndUpdate(pos.below(), Blocks.AIR.defaultBlockState());
        helper.assertTrue(FractionalSeatExitSafety.assessStanding(level, player, feet).code().equals("EXIT_SUPPORT_NO_SUPPORT"), "missing support is distinct from a blocked body");
        level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        helper.assertTrue(FractionalSeatExitSafety.assessStanding(level, player, feet).code().equals("EXIT_BODY_BLOCK_COLLISION"), "exit body blockage has its own reason");
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState()); player.setPos(feet.add(0, -.1, 0));
        UUID uid = UUID.randomUUID();
        var seat = new FractionalSeatLayout.Seat(uid, "QA", new FractionalSeatData.Anchor(feet.x, feet.y, feet.z, 0),
            feet.add(0, .455, 0), feet.add(0, .5, 0), feet, feet.add(0, 0, -1), FractionalSeatFootprint.empty(uid));
        helper.assertFalse(FractionalSeatExitSafety.mountedBodyClear(level, player, seat), "actual below-floor passenger body is never clipped away");
        helper.assertTrue(FractionalSeatExitSafety.assessMountedBody(level, player, seat).code().equals("MOUNTED_BODY_BLOCK_COLLISION"), "positioned body failure is identified");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void fractional_entry_diagnostics_share_predicates_and_measure_default_marker_fixture(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos pos = helper.absolutePos(new BlockPos(5, 3, 5));
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, 3, 2)))
            level.setBlockAndUpdate(p, p.getY() == pos.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
            new GameProfile(UUID.randomUUID(), "FracDiagQA"), ClientInformation.createDefault());
        Vec3 floor = new Vec3(pos.getX() + .5, pos.getY(), pos.getZ() + .5); player.setPos(floor.add(0, 0, -2));
        ArmorStand marker = new ArmorStand(EntityType.ARMOR_STAND, level); marker.setPos(floor.add(0, .455, 0));
        CompoundTag saved = new CompoundTag(); marker.saveWithoutId(saved); saved.putBoolean("Marker", true); marker.load(saved);
        UUID uid = UUID.randomUUID();
        var seat = new FractionalSeatLayout.Seat(uid, "QA", new FractionalSeatData.Anchor(floor.x, floor.y, floor.z, 0),
            floor.add(0, .455, 0), floor.add(0, .5, 0), floor, floor.add(0, 0, -1), FractionalSeatFootprint.empty(uid));
        var assessment = FractionalSeatExitSafety.diagnoseEntry(level, player, marker, seat);
        var normal = FractionalSeatExitSafety.assessEntry(level, player, marker, seat);
        helper.assertTrue(assessment.allowed() == FractionalSeatExitSafety.canSit(level, player, marker, seat)
            && normal.allowed() == assessment.allowed(), "normal entry and full diagnostics share the exact predicates");
        helper.assertTrue(normal.checks().size() == 1, "a rejected predicted body short-circuits normal entry before other 64-grid stages");
        // This is a source-derived fixture until this native test actually runs: marker +.455 minus
        // default player vehicle attachment +.6 predicts feet -.145 relative to the authored floor.
        helper.assertTrue(Math.abs((.455 - .6) - (-.145)) < 1e-12, "source-derived attachment fixture arithmetic");
        helper.assertTrue(Math.abs(assessment.predictedFeet().y - floor.y - (-.145)) < 1e-6, "native default marker/player attachment agrees with source fixture");
        helper.assertTrue(assessment.firstFailure().code().equals("PREDICTED_BODY_BLOCK_COLLISION"), "below-floor predicted pose is distinguished from exit obstruction");
        helper.assertTrue(assessment.checks().get(1).code().equals("FLOOR_BODY_OK") && assessment.checks().get(2).allowed(), "floor-anchor envelope and planned exit are evaluated separately");
        helper.assertTrue(FractionalSeatExitSafety.exit(level, player, seat).isPresent() == FractionalSeatExitSafety.assessExit(level, player, seat).allowed(), "exit and diagnostic result agree");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 60)
    public static void fractional_diagnostics_distinguish_retained_host_and_other_owner(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos pos = helper.absolutePos(new BlockPos(5, 3, 5));
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, 3, 2))) level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        BlockPos cell = pos.below(); level.setBlockAndUpdate(cell, FractionalSeatHost.ANDESITE_BOTTOM_SLAB);
        Vec3 floor = new Vec3(pos.getX() + .5, pos.getY() - .5, pos.getZ() + .5);
        UUID own = UUID.randomUUID(), other = UUID.randomUUID(); var anchor = new FractionalSeatData.Anchor(floor.x, floor.y, floor.z, 0);
        int a = (32 * 64 + 40) * 64 + 32, b = a + 2;
        var ownContribution = new FractionalSeatData.Contribution(own, anchor, new int[]{a, a + 1}, FractionalSeatGeometryProfile.HIGHBACK_150);
        var otherContribution = new FractionalSeatData.Contribution(other, anchor, new int[]{b, b + 1}, FractionalSeatGeometryProfile.HIGHBACK_150);
        var ownFootprint = new FractionalSeatFootprint(own, Map.of(cell, ownContribution));
        var otherFootprint = new FractionalSeatFootprint(other, Map.of(cell, otherContribution));
        FractionalSeatCells.installWithVerifiedSlabHost(level, ownFootprint);
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
            new GameProfile(UUID.randomUUID(), "FracOwnerQA"), ClientInformation.createDefault());
        var seat = new FractionalSeatLayout.Seat(own, "QA", anchor, floor.add(0, .455, 0), floor.add(0, .5, 0), floor, floor.add(0, 0, -1), ownFootprint);
        player.setPos(floor.add(0, -.145, 0));
        var host = FractionalSeatExitSafety.assessMountedBody(level, player, seat);
        helper.assertTrue(host.code().equals("MOUNTED_BODY_BLOCK_COLLISION") && host.block().equals(cell)
            && host.retainedHostCollision() && host.blockingOwners().isEmpty(), "retained host is identified without blaming ignored own chair voxels");
        FractionalSeatCells.installWithVerifiedSlabHost(level, otherFootprint); player.setPos(floor);
        var owner = FractionalSeatExitSafety.assessMountedBody(level, player, seat);
        helper.assertTrue(!owner.allowed() && !owner.retainedHostCollision() && owner.blockingOwners().equals(java.util.List.of(other)), "other owner UID remains solid and is attributed precisely");
        helper.assertTrue(FractionalSeatExitSafety.mountedBodyClear(level, player, seat) == owner.allowed(), "positioned body gate and diagnostic predicate agree");
        FractionalSeatCells.remove(level, otherFootprint); FractionalSeatCells.remove(level, ownFootprint);
        helper.succeed();
    }
    private FractionalSeatSafetyGameTests() {}
}
