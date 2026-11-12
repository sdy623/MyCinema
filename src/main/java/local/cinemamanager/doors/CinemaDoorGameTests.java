package local.cinemamanager.doors;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native tests. Compile offline; run only in the authorized NeoForge GameTest server later. */
@GameTestHolder("mycinema")
@PrefixGameTestTemplate(false)
public final class CinemaDoorGameTests {
    private static CinemaDoorBlock block(String finish) {
        Block block=BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("mycinema","cinema_door_"+finish));
        if (!(block instanceof CinemaDoorBlock door)) throw new AssertionError("Register cinema_door_"+finish+" before running door tests");
        if (!(door.asItem() instanceof CinemaDoorItem)) throw new AssertionError("Register CinemaDoorItem for "+finish);
        return door;
    }
    private static void place(GameTestHelper h,CinemaDoorBlock door,BlockPos relative,Direction facing,DoorHingeSide hinge) {
        h.setBlock(relative.below(),Blocks.STONE);
        BlockState lower=door.defaultBlockState().setValue(CinemaDoorBlock.FACING,facing).setValue(CinemaDoorBlock.HINGE,hinge);
        for(DoorPart part:DoorPart.values()) h.getLevel().setBlock(h.absolutePos(relative.above(part.offset())),lower.setValue(CinemaDoorBlock.PART,part),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        h.assertTrue(door.isComplete(h.getLevel(),h.absolutePos(relative)),"complete three-metre fixture");
    }
    private static void assertLeaf(GameTestHelper h,BlockPos p,boolean open,boolean powered) {
        for(DoorPart part:DoorPart.values()) {
            h.assertBlockProperty(p.above(part.offset()),CinemaDoorBlock.PART,part);
            h.assertBlockProperty(p.above(part.offset()),CinemaDoorBlock.OPEN,open);
            h.assertBlockProperty(p.above(part.offset()),CinemaDoorBlock.POWERED,powered);
        }
    }
    private static void assertGone(GameTestHelper h,BlockPos p) { for(int y=0;y<3;y++) h.assertBlockPresent(Blocks.AIR,p.above(y)); }

    @GameTest(template="empty",timeoutTicks=40)
    public static void cinema_door_all_states_geometry_and_palette(GameTestHelper h) {
        for(String finish:new String[]{"wood","metal"}) {
            CinemaDoorBlock door=block(finish);
            h.assertTrue(door.getStateDefinition().getPossibleStates().size()==576,"all 576 geometry, threshold and access states registered");
            for(BlockState state:door.getStateDefinition().getPossibleStates()) {
                var shape=state.getCollisionShape(h.getLevel(),h.absolutePos(new BlockPos(2,2,2)));
                var expected=DoorGeometry.shape(state.getValue(CinemaDoorBlock.FACING),state.getValue(CinemaDoorBlock.HINGE),state.getValue(CinemaDoorBlock.OPEN),state.getValue(CinemaDoorBlock.PART),state.getValue(CinemaDoorBlock.HALF_DROP));
                h.assertFalse(Shapes.joinIsNotEmpty(shape,expected,BooleanOp.NOT_SAME),"native block collision agrees with geometry contract");
                h.assertTrue(state.getLightBlock(h.getLevel(),BlockPos.ZERO)==(state.getValue(CinemaDoorBlock.OPEN)?0:15),"every closed section is light opaque");
                BlockState restored=NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),NbtUtils.writeBlockState(state));
                h.assertTrue(restored==state,"all properties roundtrip through actual blockstate palette NBT");
            }
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=70)
    public static void cinema_door_manual_double_and_any_height_redstone(GameTestHelper h) {
        CinemaDoorBlock door=block("metal");BlockPos a=new BlockPos(3,1,3),b=a.east();
        place(h,door,a,Direction.NORTH,DoorHingeSide.LEFT);place(h,door,b,Direction.NORTH,DoorHingeSide.RIGHT);
        for(int i=0;i<6;i++) {
            h.useBlock((i%2==0?a:b).above(i%3));
            assertLeaf(h,a,i%2==0,false);assertLeaf(h,b,i%2==0,false);
        }
        BlockPos source=b.above(2).south();h.setBlock(source,Blocks.REDSTONE_BLOCK);
        h.runAfterDelay(2,()->{
            assertLeaf(h,a,true,true);assertLeaf(h,b,true,true);
            h.useBlock(a.above());assertLeaf(h,a,false,true);assertLeaf(h,b,false,true);
            h.runAfterDelay(22,()->{
                assertLeaf(h,a,false,true);assertLeaf(h,b,false,true);
                h.setBlock(source,Blocks.AIR);
                h.runAfterDelay(2,()->{
                    assertLeaf(h,a,false,false);assertLeaf(h,b,false,false);
                    h.setBlock(a.below(),Blocks.REDSTONE_BLOCK);
                    h.runAfterDelay(2,()->{
                        assertLeaf(h,a,true,true);assertLeaf(h,b,true,true);
                        h.setBlock(a.below(),Blocks.STONE);
                        h.runAfterDelay(2,()->{assertLeaf(h,a,false,false);assertLeaf(h,b,false,false);h.succeed();});
                    });
                });
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void cinema_door_break_each_part_support_replace_and_repair(GameTestHelper h) {
        CinemaDoorBlock door=block("wood");BlockPos p=new BlockPos(3,1,3),other=p.east();
        for(int part=0;part<3;part++) {
            place(h,door,p,Direction.NORTH,DoorHingeSide.LEFT);
            place(h,door,other,Direction.NORTH,DoorHingeSide.RIGHT);
            h.getLevel().destroyBlock(h.absolutePos(p.above(part)),true);
            assertGone(h,p);assertLeaf(h,other,false,false);
            h.assertItemEntityCountIs(door.asItem(),p,6,part+1);
            // Replacing any segment cleans its own remaining parts without inventing drops.
            h.setBlock(other.above(part),Blocks.STONE);
            for(int y=0;y<3;y++) if(y!=part) h.assertBlockPresent(Blocks.AIR,other.above(y));
            h.setBlock(other.above(part),Blocks.AIR);
        }
        place(h,door,p,Direction.NORTH,DoorHingeSide.LEFT);
        h.setBlock(p.below(),Blocks.AIR);
        h.runAfterDelay(22,()->{
            assertGone(h,p);h.assertItemEntityCountIs(door.asItem(),p,6,4);
            place(h,door,p,Direction.NORTH,DoorHingeSide.LEFT);
            // A missing/incorrect part from external editing is rejected as an incomplete leaf.
            h.getLevel().setBlock(h.absolutePos(p.above()),door.defaultBlockState().setValue(CinemaDoorBlock.PART,DoorPart.MIDDLE).setValue(CinemaDoorBlock.FACING,Direction.SOUTH),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            h.runAfterDelay(22,()->{assertGone(h,p);h.assertItemEntityCountIs(door.asItem(),p,6,5);h.succeed();});
        });
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void cinema_door_real_item_checks_three_metres(GameTestHelper h) {
        CinemaDoorBlock door=block("wood");BlockPos p=new BlockPos(3,1,3);h.setBlock(p.below(),Blocks.STONE);
        ItemStack stack=new ItemStack(door.asItem(),8);
        var context=new DirectionalPlaceContext(h.getLevel(),h.absolutePos(p),Direction.NORTH,stack,Direction.UP);
        h.setBlock(p.above(2),Blocks.STONE);
        h.assertTrue(door.getStateForPlacement(context)==null,"third metre obstruction refuses placement");
        h.assertFalse(((CinemaDoorItem)door.asItem()).place(context).consumesAction(),"real item does not place under2m headroom");
        h.assertTrue(stack.getCount()==8,"failed placement consumes no item");h.assertBlockPresent(Blocks.AIR,p);
        h.setBlock(p.above(2),Blocks.AIR);
        h.assertTrue(((CinemaDoorItem)door.asItem()).place(context).consumesAction(),"real item places full3m leaf");
        assertLeaf(h,p,false,false);h.assertTrue(stack.getCount()==7,"exactly one item consumed");
        // Actual item placement infers the reciprocal hinge for an adjacent double leaf.
        BlockPos q=p.east();h.setBlock(q.below(),Blocks.STONE);
        var second=new DirectionalPlaceContext(h.getLevel(),h.absolutePos(q),Direction.NORTH,stack,Direction.UP);
        h.assertTrue(((CinemaDoorItem)door.asItem()).place(second).consumesAction(),"second leaf placement succeeds");
        h.assertBlockProperty(p,CinemaDoorBlock.HINGE,DoorHingeSide.LEFT);
        h.assertBlockProperty(q,CinemaDoorBlock.HINGE,DoorHingeSide.RIGHT);
        h.useBlock(q.above(2));assertLeaf(h,p,true,false);assertLeaf(h,q,true,false);h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=80)
    public static void cinema_door_manager_admission_output_controls_double(GameTestHelper h) {
        CinemaDoorBlock door=block("wood");BlockPos a=new BlockPos(3,1,3),b=a.east();
        place(h,door,a,Direction.NORTH,DoorHingeSide.LEFT);place(h,door,b,Direction.NORTH,DoorHingeSide.RIGHT);
        BlockPos managerPos=new BlockPos(8,1,3),outputPos=b.above().south();
        h.setBlock(managerPos,BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("mycinema","manager_terminal")));
        local.cinemamanager.ManagerTile manager=h.getBlockEntity(managerPos);
        var hall=new local.cinemamanager.model.ManagerState.Hall();manager.state.halls.add(hall);
        h.setBlock(outputPos,BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("mycinema","entry_output")));
        local.cinemamanager.EndpointTile endpoint=h.getBlockEntity(outputPos);
        endpoint.manager=manager.getBlockPos();endpoint.managerIdentity=manager.identity;endpoint.hallId=hall.id;
        hall.outputs.add(endpoint.getBlockPos().asLong());
        h.runAfterDelay(12,()->{
            h.assertBlockProperty(outputPos,local.cinemamanager.EndpointBlock.POWER,0);assertLeaf(h,a,false,false);assertLeaf(h,b,false,false);
            local.cinemamanager.model.ManagerState.apply(hall,local.cinemamanager.model.ManagerState.Scene.OPEN);manager.dirty();
            h.runAfterDelay(12,()->{
                h.assertBlockProperty(outputPos,local.cinemamanager.EndpointBlock.POWER,15);assertLeaf(h,a,true,true);assertLeaf(h,b,true,true);
                local.cinemamanager.model.ManagerState.apply(hall,local.cinemamanager.model.ManagerState.Scene.PLAY);h.assertTrue(hall.open,"feature keeps business open while admission hold closes");manager.dirty();
                h.runAfterDelay(12,()->{
                    h.assertBlockProperty(outputPos,local.cinemamanager.EndpointBlock.POWER,0);assertLeaf(h,a,false,false);assertLeaf(h,b,false,false);
                    local.cinemamanager.model.ManagerState.apply(hall,local.cinemamanager.model.ManagerState.Scene.OPEN);manager.dirty();
                    h.runAfterDelay(12,()->{
                        assertLeaf(h,a,true,true);h.setBlock(managerPos,Blocks.AIR);
                        h.runAfterDelay(12,()->{h.assertBlockProperty(outputPos,local.cinemamanager.EndpointBlock.POWER,0);assertLeaf(h,a,false,false);assertLeaf(h,b,false,false);h.succeed();});
                    });
                });
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void cinema_door_all_facing_item_pairs_have_continuous_clearance(GameTestHelper h) {
        CinemaDoorBlock door=block("wood");ItemStack stack=new ItemStack(door.asItem(),32);
        for(Direction facing:Direction.Plane.HORIZONTAL) for(boolean reverse:new boolean[]{false,true}) {
            BlockPos a=new BlockPos(6,1,6),b=a.relative(DoorGeometry.partnerDirection(facing,DoorHingeSide.LEFT));
            h.setBlock(a.below(),Blocks.STONE);h.setBlock(b.below(),Blocks.STONE);
            for(BlockPos p:reverse?List.of(b,a):List.of(a,b)) {
                var context=new DirectionalPlaceContext(h.getLevel(),h.absolutePos(p),facing,stack,Direction.UP);
                h.assertTrue(((CinemaDoorItem)door.asItem()).place(context).consumesAction(),"both placement orders succeed for"+facing);
            }
            h.assertBlockProperty(a,CinemaDoorBlock.HINGE,DoorHingeSide.LEFT);h.assertBlockProperty(b,CinemaDoorBlock.HINGE,DoorHingeSide.RIGHT);
            h.assertTrue(door.setOpen(null,h.getLevel(),h.absolutePos(a.above(2)),true),"complete double assembly opens");
            assertLeaf(h,a,true,false);assertLeaf(h,b,true,false);
            BlockPos wa=h.absolutePos(a),wb=h.absolutePos(b);double t=DoorGeometry.THICKNESS,e=.0001;
            int minX=Math.min(wa.getX(),wb.getX()),minZ=Math.min(wa.getZ(),wb.getZ());
            AABB clear=facing.getAxis()==Direction.Axis.Z?
                new AABB(minX+t+e,wa.getY()+e,minZ+e,minX+2-t-e,wa.getY()+3-e,minZ+1-e):
                new AABB(minX+e,wa.getY()+e,minZ+t+e,minX+1-e,wa.getY()+3-e,minZ+2-t-e);
            h.assertTrue(h.getLevel().noCollision(clear),"actual continuous1.625m width by3m height free, including every depth slice");
            h.assertFalse(h.getLevel().noCollision(clear.inflate(facing.getAxis()==Direction.Axis.Z?.01:0,0,facing.getAxis()==Direction.Axis.X?.01:0)),"actual clearance stops at physical jamb leaves");
            h.getLevel().destroyBlock(wa,false);h.getLevel().destroyBlock(wb,false);
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void cinema_door_palette_restore_and_segment_reconciliation(GameTestHelper h) {
        CinemaDoorBlock door=block("wood");BlockPos p=new BlockPos(3,1,3);
        place(h,door,p,Direction.WEST,DoorHingeSide.RIGHT);door.setOpen(null,h.getLevel(),h.absolutePos(p),true);
        BlockState[] saved=new BlockState[3];
        for(int y=0;y<3;y++) saved[y]=NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),NbtUtils.writeBlockState(h.getBlockState(p.above(y))));
        h.getLevel().destroyBlock(h.absolutePos(p.above()),false);assertGone(h,p);
        for(int y=0;y<3;y++) h.getLevel().setBlock(h.absolutePos(p.above(y)),saved[y],Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        assertLeaf(h,p,true,false);h.useBlock(p.above(2));assertLeaf(h,p,false,false);
        h.getLevel().setBlock(h.absolutePos(p.above()),saved[1],Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        h.runAfterDelay(22,()->{assertLeaf(h,p,false,false);h.succeed();});
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void cinema_door_actual_collision_and_sealed_light(GameTestHelper h) {
        CinemaDoorBlock door=block("metal");
        // A fully roofed opaque tunnel, preventing skylight and side-route contamination.
        for(int x=1;x<=4;x++) for(int y=0;y<=4;y++) for(int z=1;z<=7;z++)
            h.setBlock(new BlockPos(x,y,z),x==1||x==4||y==0||y==4||z==1||z==7?Blocks.STONE:Blocks.AIR);
        BlockPos left=new BlockPos(3,1,4),right=new BlockPos(2,1,4);
        place(h,door,left,Direction.SOUTH,DoorHingeSide.LEFT);place(h,door,right,Direction.SOUTH,DoorHingeSide.RIGHT);
        h.setBlock(new BlockPos(2,2,2),Blocks.GLOWSTONE);
        for(int y=0;y<3;y++) {
            BlockPos w=h.absolutePos(right.above(y));
            h.assertFalse(h.getLevel().noCollision(new AABB(w.getX()+.3,w.getY()+.1,w.getZ()+.05,w.getX()+.7,w.getY()+.9,w.getZ()+.1)),"actual closed panel collision at metre"+y);
        }
        h.runAfterDelay(20,()->{
            DoorLightReport.write(h,"block_light","closed",List.of(left,right),List.of(new BlockPos(2,3,5),new BlockPos(2,2,6)));
            h.assertTrue(h.getLevel().getBrightness(LightLayer.BLOCK,h.absolutePos(new BlockPos(2,3,5)))==0,"closed third metre does not leak light");
            h.useBlock(left.above(2));assertLeaf(h,left,true,false);assertLeaf(h,right,true,false);
            for(int y=0;y<3;y++) {
                BlockPos w=h.absolutePos(right.above(y));
                h.assertTrue(h.getLevel().noCollision(new AABB(w.getX()+.3,w.getY()+.1,w.getZ()+.05,w.getX()+.7,w.getY()+.9,w.getZ()+.1)),"old closed plane becomes physically clear at metre"+y);
                h.assertFalse(h.getLevel().noCollision(new AABB(w.getX()+.05,w.getY()+.1,w.getZ()+.3,w.getX()+.1,w.getY()+.9,w.getZ()+.7)),"open panel collides only beside outer jamb at metre"+y);
            }
            h.runAfterDelay(20,()->{
                DoorLightReport.write(h,"block_light","open",List.of(left,right),List.of(new BlockPos(2,3,5),new BlockPos(2,2,6)));
                h.assertTrue(h.getLevel().getBrightness(LightLayer.BLOCK,h.absolutePos(new BlockPos(2,3,5)))>0,"opened3m passage transmits real block light");
                h.useBlock(right);h.runAfterDelay(20,()->{DoorLightReport.write(h,"block_light","reclosed",List.of(left,right),List.of(new BlockPos(2,3,5),new BlockPos(2,2,6)));h.assertTrue(h.getLevel().getBrightness(LightLayer.BLOCK,h.absolutePos(new BlockPos(2,3,5)))==0,"reclosing restores actual light seal");h.succeed();});
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=140)
    public static void cinema_door_outdoor_skylight_dark_room(GameTestHelper h) {
        CinemaDoorBlock door=block("wood");
        // Sealed3m-tall room: only its two-leaf front opening connects to outdoors.
        for(int x=1;x<=6;x++) for(int y=0;y<=4;y++) for(int z=4;z<=9;z++)
            h.setBlock(new BlockPos(x,y,z),x==1||x==6||y==0||y==4||z==4||z==9?Blocks.STONE:Blocks.AIR);
        BlockPos left=new BlockPos(4,1,4),right=new BlockPos(3,1,4);
        for(BlockPos p:List.of(left,right)) for(int y=0;y<3;y++) h.setBlock(p.above(y),Blocks.AIR);
        place(h,door,left,Direction.SOUTH,DoorHingeSide.LEFT);place(h,door,right,Direction.SOUTH,DoorHingeSide.RIGHT);
        BlockPos managerPos=new BlockPos(8,1,4),outputPos=left.below();
        h.setBlock(managerPos,local.cinemamanager.CinemaManager.MANAGER.get());
        local.cinemamanager.ManagerTile manager=h.getBlockEntity(managerPos);
        var hall=new local.cinemamanager.model.ManagerState.Hall();hall.terminalIdentity=java.util.UUID.randomUUID().toString();manager.state.halls.add(hall);
        h.setBlock(outputPos,local.cinemamanager.CinemaManager.ENTRY.get());
        local.cinemamanager.EndpointTile endpoint=h.getBlockEntity(outputPos);endpoint.manager=manager.getBlockPos();endpoint.managerIdentity=manager.identity;endpoint.hallId=hall.id;hall.outputs.add(endpoint.getBlockPos().asLong());manager.dirty();
        // Clear only two fixture-owned outdoor columns to the sky; never touch a user world.
        for(int x=3;x<=4;x++) {
            BlockPos column=h.absolutePos(new BlockPos(x,1,3));
            for(int y=column.getY();y<h.getLevel().getMaxBuildHeight();y++)
                h.getLevel().setBlock(new BlockPos(column.getX(),y,column.getZ()),Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        }
        List<BlockPos> inside=List.of(new BlockPos(3,1,5),new BlockPos(3,2,5),new BlockPos(3,3,5),new BlockPos(3,2,7),new BlockPos(4,3,8));
        List<BlockPos> samples=new java.util.ArrayList<>(inside);BlockPos outside=new BlockPos(3,3,3);samples.add(outside);
        h.runAfterDelay(30,()->{
            DoorLightReport.write(h,"sky_light","closed",List.of(left,right),samples);
            h.assertTrue(h.getLevel().canSeeSky(h.absolutePos(outside)),"outdoor source really sees sky");
            h.assertTrue(h.getLevel().getBrightness(LightLayer.SKY,h.absolutePos(outside))==15,"actual outdoor sky light is15");
            for(BlockPos p:inside) h.assertTrue(h.getLevel().getBrightness(LightLayer.SKY,h.absolutePos(p))==0,"closed interior sky light0 at"+p);
            local.cinemamanager.model.ManagerState.apply(hall,local.cinemamanager.model.ManagerState.Scene.OPEN);manager.dirty();
            h.runAfterDelay(30,()->{
                DoorLightReport.write(h,"sky_light","open",List.of(left,right),samples);
                for(BlockPos p:inside) h.assertTrue(h.getLevel().getBrightness(LightLayer.SKY,h.absolutePos(p))>0,"open passage restores sky light at"+p);
                h.assertBlockProperty(outputPos,local.cinemamanager.EndpointBlock.POWER,15);assertLeaf(h,left,true,true);assertLeaf(h,right,true,true);
                local.cinemamanager.model.ManagerState.apply(hall,local.cinemamanager.model.ManagerState.Scene.PLAY);h.assertTrue(hall.open&&!hall.admissionHeld,"feature scene leaves business open and releases entry hold");manager.dirty();
                h.runAfterDelay(30,()->{
                    DoorLightReport.write(h,"sky_light","reclosed",List.of(left,right),samples);
                    for(BlockPos p:inside) h.assertTrue(h.getLevel().getBrightness(LightLayer.SKY,h.absolutePos(p))==0,"reclosed interior sky light0 at"+p);
                    h.assertBlockProperty(outputPos,local.cinemamanager.EndpointBlock.POWER,0);assertLeaf(h,left,false,false);assertLeaf(h,right,false,false);h.succeed();
                });
            });
        });
    }
}
