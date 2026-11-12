package local.cinemamanager.doors;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mycinema")
@PrefixGameTestTemplate(false)
public final class StaffDoorGameTests {
    private static CinemaDoorBlock door(String finish) {
        return (CinemaDoorBlock) BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("mycinema", "cinema_door_" + finish));
    }
    private static void place(GameTestHelper h, CinemaDoorBlock door, BlockPos p, Direction facing, DoorHingeSide hinge, DoorAccess access) {
        h.setBlock(p.below(), Blocks.STONE);
        BlockState state=door.defaultBlockState().setValue(CinemaDoorBlock.FACING,facing).setValue(CinemaDoorBlock.HINGE,hinge).setValue(CinemaDoorBlock.ACCESS,access);
        for(DoorPart part:DoorPart.values()) h.getLevel().setBlock(h.absolutePos(p.above(part.offset())),state.setValue(CinemaDoorBlock.PART,part),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        h.assertTrue(door.isComplete(h.getLevel(),h.absolutePos(p)),"complete staff door");
    }
    private static void assertLeaf(GameTestHelper h, BlockPos p, boolean open, boolean powered, DoorAccess access) {
        for(DoorPart part:DoorPart.values()) {
            h.assertBlockProperty(p.above(part.offset()),CinemaDoorBlock.OPEN,open);
            h.assertBlockProperty(p.above(part.offset()),CinemaDoorBlock.POWERED,powered);
            h.assertBlockProperty(p.above(part.offset()),CinemaDoorBlock.ACCESS,access);
        }
    }
    private static void position(Player player, BlockPos p, Direction facing, boolean front) {
        Vec3 center=Vec3.atBottomCenterOf(p); double d=front?1.5:-1.5;
        player.setPos(center.x+facing.getStepX()*d,center.y,center.z+facing.getStepZ()*d);
    }
    private static void click(GameTestHelper h, CinemaDoorBlock door, BlockPos p, Player player, Direction claimedFace) {
        BlockPos absolute=h.absolutePos(p);
        door.useWithoutItem(h.getBlockState(p),h.getLevel(),absolute,player,new BlockHitResult(Vec3.atCenterOf(absolute),claimedFace,absolute,false));
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void staff_door_all_sides_sections_and_forged_hit(GameTestHelper h) {
        Player player=h.makeMockPlayer(GameType.SURVIVAL);
        for(String finish:List.of("wood","metal")) for(Direction facing:Direction.Plane.HORIZONTAL) for(DoorAccess mode:List.of(DoorAccess.STAFF_FRONT,DoorAccess.STAFF_BACK)) {
            CinemaDoorBlock door=door(finish);BlockPos p=new BlockPos(5,1,5);
            place(h,door,p,facing,DoorHingeSide.LEFT,mode);
            for(DoorPart part:DoorPart.values()) {
                position(player,h.absolutePos(p),facing,mode==DoorAccess.STAFF_FRONT);
                click(h,door,p.above(part.offset()),player,facing.getOpposite());
                assertLeaf(h,p,false,false,mode);
                h.assertFalse(door.setOpen(player,h.getLevel(),h.absolutePos(p.above(part.offset())),true),"player API cannot bypass entry side");
                position(player,h.absolutePos(p),facing,mode!=DoorAccess.STAFF_FRONT);
                click(h,door,p.above(part.offset()),player,facing);
                assertLeaf(h,p,false,false,mode);
                h.assertFalse(door.setOpen(player,h.getLevel(),h.absolutePos(p),true),"inside reach outside controller zone cannot create a permanently open unpowered gate");
                h.setBlock(p.below(),Blocks.REDSTONE_BLOCK);
                assertLeaf(h,p,true,true,mode);
                h.assertTrue(door.setOpen(null,h.getLevel(),h.absolutePos(p),false),"trusted server close retained under steady power");
                position(player,h.absolutePos(p),facing,mode==DoorAccess.STAFF_FRONT);
                click(h,door,p.above(part.offset()),player,facing.getOpposite());assertLeaf(h,p,false,true,mode);
                position(player,h.absolutePos(p),facing,mode!=DoorAccess.STAFF_FRONT);
                click(h,door,p.above(part.offset()),player,facing);
                assertLeaf(h,p,true,true,mode);
                click(h,door,p.above(part.offset()),player,facing);
                assertLeaf(h,p,true,true,mode);
                h.assertFalse(door.setOpen(player,h.getLevel(),h.absolutePos(p),false),"inside player cannot defeat persistent external open hold");
                h.setBlock(p.below(),Blocks.STONE);assertLeaf(h,p,false,false,mode);
            }
            h.getLevel().destroyBlock(h.absolutePos(p),false);
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void staff_door_mixed_pair_and_redstone_hold(GameTestHelper h) {
        CinemaDoorBlock door=door("metal");BlockPos a=new BlockPos(5,1,4),b=a.south();
        place(h,door,a,Direction.WEST,DoorHingeSide.RIGHT,DoorAccess.STAFF_FRONT);
        place(h,door,b,Direction.WEST,DoorHingeSide.LEFT,DoorAccess.PUBLIC);
        Player player=h.makeMockPlayer(GameType.SURVIVAL);position(player,h.absolutePos(a),Direction.WEST,true);
        for(BlockPos p:List.of(a,b)) for(DoorPart part:DoorPart.values()) click(h,door,p.above(part.offset()),player,Direction.EAST);
        assertLeaf(h,a,false,false,DoorAccess.STAFF_FRONT);assertLeaf(h,b,false,false,DoorAccess.PUBLIC);
        h.setBlock(a.below(),Blocks.REDSTONE_BLOCK);
        h.runAfterDelay(2,()->{
            assertLeaf(h,a,true,true,DoorAccess.STAFF_FRONT);assertLeaf(h,b,true,true,DoorAccess.PUBLIC);
            position(player,h.absolutePos(a),Direction.WEST,false);click(h,door,b.above(2),player,Direction.WEST);
            h.runAfterDelay(22,()->{
                assertLeaf(h,a,true,true,DoorAccess.STAFF_FRONT);assertLeaf(h,b,true,true,DoorAccess.PUBLIC);
                h.setBlock(a.below(),Blocks.STONE);
                h.runAfterDelay(2,()->{
                    assertLeaf(h,a,false,false,DoorAccess.STAFF_FRONT);assertLeaf(h,b,false,false,DoorAccess.PUBLIC);
                    h.setBlock(b.above(2).east(),Blocks.REDSTONE_BLOCK);
                    h.runAfterDelay(2,()->{
                        assertLeaf(h,a,true,true,DoorAccess.STAFF_FRONT);assertLeaf(h,b,true,true,DoorAccess.PUBLIC);
                        h.setBlock(b.above(2).east(),Blocks.AIR);
                        h.runAfterDelay(2,()->{assertLeaf(h,a,false,false,DoorAccess.STAFF_FRONT);assertLeaf(h,b,false,false,DoorAccess.PUBLIC);h.succeed();});
                    });
                });
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void staff_door_legacy_default_and_saved_reconciliation(GameTestHelper h) {
        CinemaDoorBlock door=door("wood");BlockPos p=new BlockPos(5,1,5);
        h.assertTrue(door.defaultBlockState().getValue(CinemaDoorBlock.ACCESS)==DoorAccess.PUBLIC,"ordinary item default remains public");
        var legacy=NbtUtils.writeBlockState(door.defaultBlockState());legacy.getCompound("Properties").remove("access");
        h.assertTrue(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),legacy).getValue(CinemaDoorBlock.ACCESS)==DoorAccess.PUBLIC,"old palettes without access load public");
        place(h,door,p,Direction.WEST,DoorHingeSide.RIGHT,DoorAccess.PUBLIC);
        Player player=h.makeMockPlayer(GameType.SURVIVAL);
        for(boolean front:new boolean[]{true,false}) {position(player,h.absolutePos(p),Direction.WEST,front);click(h,door,p,player,Direction.EAST);assertLeaf(h,p,true,false,DoorAccess.PUBLIC);click(h,door,p,player,Direction.WEST);assertLeaf(h,p,false,false,DoorAccess.PUBLIC);}
        h.getLevel().destroyBlock(h.absolutePos(p),false);
        place(h,door,p,Direction.WEST,DoorHingeSide.RIGHT,DoorAccess.STAFF_FRONT);
        BlockState[] saved=new BlockState[3];
        for(int y=0;y<3;y++) saved[y]=NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),NbtUtils.writeBlockState(h.getBlockState(p.above(y))));
        h.getLevel().destroyBlock(h.absolutePos(p),false);
        for(int y=0;y<3;y++)h.getLevel().setBlock(h.absolutePos(p.above(y)),saved[y],Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        h.getLevel().setBlock(h.absolutePos(p.above()),saved[1].setValue(CinemaDoorBlock.ACCESS,DoorAccess.PUBLIC),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        position(player,h.absolutePos(p),Direction.WEST,true);click(h,door,p.above(),player,Direction.EAST);
        h.assertBlockProperty(p,CinemaDoorBlock.OPEN,false);
        h.runAfterDelay(22,()->{
            h.assertBlockProperty(p,CinemaDoorBlock.ACCESS,DoorAccess.STAFF_FRONT);
            h.assertBlockProperty(p.above(),CinemaDoorBlock.ACCESS,DoorAccess.PUBLIC);
            h.assertBlockProperty(p.above(2),CinemaDoorBlock.ACCESS,DoorAccess.STAFF_FRONT);
            h.assertBlockProperty(p,CinemaDoorBlock.OPEN,false);
            // Reverse mismatch must also stay protected; do not discard a protected upper mark.
            h.getLevel().setBlock(h.absolutePos(p),saved[0].setValue(CinemaDoorBlock.ACCESS,DoorAccess.PUBLIC),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            position(player,h.absolutePos(p),Direction.WEST,true);click(h,door,p,player,Direction.EAST);
            h.assertBlockProperty(p,CinemaDoorBlock.OPEN,false);
            h.setBlock(p.below(),Blocks.REDSTONE_BLOCK);door.setOpen(null,h.getLevel(),h.absolutePos(p),false);
            click(h,door,p,player,Direction.EAST);h.assertBlockProperty(p,CinemaDoorBlock.OPEN,false);
            h.runAfterDelay(22,()->{
                h.assertBlockProperty(p.above(2),CinemaDoorBlock.ACCESS,DoorAccess.STAFF_FRONT);
                h.assertBlockProperty(p,CinemaDoorBlock.OPEN,false);
                h.getLevel().setBlock(h.absolutePos(p.above()),saved[1].setValue(CinemaDoorBlock.ACCESS,DoorAccess.STAFF_BACK),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
                position(player,h.absolutePos(p),Direction.WEST,false);click(h,door,p,player,Direction.WEST);
                h.assertBlockProperty(p,CinemaDoorBlock.OPEN,false);
                h.assertTrue(door.setOpen(null,h.getLevel(),h.absolutePos(p),true),"contradictory access fails closed manually but external egress still works");
                h.succeed();
            });
        });
    }
}
