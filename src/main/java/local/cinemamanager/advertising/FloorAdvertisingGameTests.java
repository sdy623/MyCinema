package local.cinemamanager.advertising;

import com.mojang.authlib.GameProfile;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.InfoTile;
import local.cinemamanager.panels.PanelGroup;
import local.cinemamanager.panels.assets.AssetAlphaMask;
import local.cinemamanager.panels.assets.AssetReference;
import local.cinemamanager.panels.assets.AssetStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Real placement, signed assembly, cold chunk restoration and physical floor-support lifecycle. */
@GameTestHolder("mycinema")
@PrefixGameTestTemplate(false)
public final class FloorAdvertisingGameTests {
    private static final BlockPos ORIGIN=new BlockPos(6,3,6);

    private static ServerPlayer actor(GameTestHelper h){
        ServerPlayer player=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"FloorAdvertisingQA"));
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(h.absolutePos(ORIGIN).getCenter().add(0,0,-3));
        return player;
    }
    private static void nearby(ServerPlayer actor,BlockPos pos,Direction facing){
        actor.setPos(pos.getCenter().add(facing.getStepX()*2,0,facing.getStepZ()*2));
    }
    private static InteractionResult click(GameTestHelper h,ServerPlayer actor,BlockPos source,Direction face){
        BlockHitResult hit=new BlockHitResult(source.getCenter().add(face.getStepX()*.5,face.getStepY()*.5,face.getStepZ()*.5),face,source,false);
        return ((BlockItem)actor.getMainHandItem().getItem()).place(new BlockPlaceContext(new UseOnContext(actor,InteractionHand.MAIN_HAND,hit)));
    }
    private static InfoTile grounded(GameTestHelper h,ServerPlayer owner,Direction facing){
        BlockPos pos=h.absolutePos(ORIGIN);
        // Catch survival drops after removing the immediate support without supporting the board itself.
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)
            h.getLevel().setBlockAndUpdate(pos.offset(x,-2,z),Blocks.STONE.defaultBlockState());
        h.getLevel().setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
        owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CinemaManager.AD_PRINTED_ITEM.get(),16));
        owner.setYRot(facing.getOpposite().toYRot());
        nearby(owner,pos,facing);
        h.assertTrue(click(h,owner,pos.below(),Direction.UP).consumesAction(),"grounded printed floor unit uses the real BlockItem path");
        InfoTile root=root(h,pos);
        assertUnit(h,root,owner,facing);
        h.assertTrue(owner.getMainHandItem().getCount()==15,"ground placement consumes exactly one printed unit");
        return root;
    }
    private static InfoTile root(GameTestHelper h,BlockPos pos){
        h.assertTrue(h.getLevel().getBlockEntity(pos) instanceof InfoTile,"expected an independent registered advertising root at "+pos);
        return (InfoTile)h.getLevel().getBlockEntity(pos);
    }
    private static void assertUnit(GameTestHelper h,InfoTile root,ServerPlayer owner,Direction facing){
        h.assertTrue(root.owner.equals(owner.getUUID())&&root.getBlockState().is(CinemaManager.AD_PRINTED.get())
                &&root.getBlockState().getValue(AdvertisingBlock.FLOOR)&&root.getBlockState().getValue(AdvertisingBlock.FACING)==facing,
                "floor unit retains matching material, placing owner and borrowed outward facing");
        h.assertTrue(root.advertising.equals(AdvertisingGeometry.DEFAULT)&&root.advertisingShape.rectangular()&&root.assembledUnits==1,
                "ordinary stacking creates an independent one-unit 1x1 rectangle");
    }
    private static InfoTile above(GameTestHelper h,ServerPlayer owner,InfoTile sourceRoot,BlockPos source){
        Direction facing=sourceRoot.getBlockState().getValue(AdvertisingBlock.FACING);
        nearby(owner,source,facing);
        owner.setYRot(facing.toYRot()); // Deliberately requests the opposite facing if the source is ignored.
        owner.setShiftKeyDown(true);
        int count=owner.getMainHandItem().getCount();
        CompoundTag before=sourceRoot.saveWithFullMetadata(h.getLevel().registryAccess());
        h.assertTrue(click(h,owner,source,Direction.UP).consumesAction(),"UP on a live matching floor root or reciprocal top proxy places a unit");
        InfoTile added=root(h,source.above());
        assertUnit(h,added,owner,facing);
        h.assertTrue(added.screenMode==ScreenMode.PICTURE&&added.panel.poster().isEmpty(),
                "new independent unit keeps blank picture content rather than copying the selected source");
        h.assertTrue(!added.identity.equals(sourceRoot.identity)&&owner.getMainHandItem().getCount()==count-1,
                "stack placement creates a fresh identity and consumes exactly one item");
        h.assertTrue(before.equals(sourceRoot.saveWithFullMetadata(h.getLevel().registryAccess())),"stack placement preserves the source identity, content and geometry");
        return added;
    }
    private static List<InfoTile> stack(GameTestHelper h,ServerPlayer owner,Direction facing,int height){
        List<InfoTile> units=new ArrayList<>();units.add(grounded(h,owner,facing));
        for(int i=1;i<height;i++)units.add(above(h,owner,units.get(i-1),units.get(i-1).getBlockPos()));
        h.assertTrue(owner.getMainHandItem().getCount()==16-height,"whole stack consumes exactly its number of physical units");
        h.assertTrue(units.stream().map(t->t.identity).distinct().count()==height,"every unassembled unit has its own identity");
        return units;
    }
    private static void resize(GameTestHelper h,ServerPlayer owner,InfoTile root,AdvertisingGeometry next){
        nearby(owner,root.getBlockPos(),root.getBlockState().getValue(AdvertisingBlock.FACING));
        AdvertisingLifecycle.resize(root,owner,PanelGroup.of(root).signature(),next);
        h.assertTrue(root.advertising.equals(next),"accepted edit retains the exact signed eighth-unit geometry");
        parts(h,root);
    }
    private static void groundUnder(GameTestHelper h,InfoTile root,AdvertisingGeometry geometry){
        Direction facing=root.getBlockState().getValue(AdvertisingBlock.FACING);
        int base=root.getBlockPos().getY()+Math.floorDiv(geometry.bottom(),8);
        for(BlockPos cell:geometry.cells(root.getBlockPos(),facing))if(cell.getY()==base)
            h.getLevel().setBlockAndUpdate(cell.below(),Blocks.STONE.defaultBlockState());
    }
    private static void parts(GameTestHelper h,InfoTile root){
        Direction facing=root.getBlockState().getValue(AdvertisingBlock.FACING);
        for(BlockPos cell:root.advertising.cells(root.getBlockPos(),facing))if(!cell.equals(root.getBlockPos())){
            h.assertTrue(h.getLevel().getBlockEntity(cell) instanceof AdvertisingPartTile,"every non-anchor geometry cell is a registered proxy");
            AdvertisingPartTile part=(AdvertisingPartTile)h.getLevel().getBlockEntity(cell);
            h.assertTrue(part.root()==root&&part.matches(root)&&part.geometry.equals(root.advertising)&&part.shape.equals(root.advertisingShape)
                    &&part.getBlockState().getValue(AdvertisingBlock.FACING)==facing&&part.getBlockState().getValue(AdvertisingBlock.FLOOR),
                    "proxy has reciprocal identity, exact signed geometry/profile and matching floor orientation");
        }
    }
    private static InfoTile assemble(GameTestHelper h,ServerPlayer owner,List<InfoTile> units,int anchor){
        InfoTile root=units.get(anchor);UUID identity=root.identity;
        var plan=AdvertisingAssembly.inspect(root);
        h.assertTrue(plan!=null&&plan.units().size()==units.size(),"explicit assembly discovers every connected compatible floor unit");
        AdvertisingGeometry expected=new AdvertisingGeometry(0,-anchor*8,8,units.size()*8);
        h.assertTrue(plan.geometry().equals(expected),"assembly preview uses signed bottom offset relative to the selected anchor");
        nearby(owner,root.getBlockPos(),root.getBlockState().getValue(AdvertisingBlock.FACING));
        AdvertisingAssembly.assemble(root,owner,plan.signature());
        h.assertTrue(root.identity.equals(identity)&&root.advertising.equals(expected)&&root.assembledUnits==units.size(),
                "assembly preserves selected identity, signed geometry and original item count");
        h.assertTrue(AdvertisingLifecycle.supported(h.getLevel(),root.getBlockPos(),root.getBlockState(),root.advertising,true),
                "bottom, middle and top anchors all resolve the true grounded base");
        parts(h,root);
        return root;
    }
    private static void rejects(GameTestHelper h,Runnable action,String message){
        boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,message);
    }
    private static void rejectedPlacement(GameTestHelper h,ServerPlayer actor,BlockPos source,Direction face,Item item,String message){
        actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(item,8));actor.setShiftKeyDown(true);
        BlockPos target=source.relative(face);var before=h.getLevel().getBlockState(target);
        h.assertFalse(click(h,actor,source,face).consumesAction(),message);
        h.assertTrue(actor.getMainHandItem().getCount()==8&&h.getLevel().getBlockState(target).equals(before),
                "rejected contextual placement consumes no item and changes no target block");
    }
    private static AABB area(GameTestHelper h){return new AABB(h.absolutePos(ORIGIN)).inflate(4,6,4);}
    private static int dropped(GameTestHelper h){
        return h.getLevel().getEntitiesOfClass(ItemEntity.class,area(h)).stream()
                .filter(e->e.getItem().is(CinemaManager.AD_PRINTED_ITEM.get())).mapToInt(e->e.getItem().getCount()).sum();
    }
    private static void gone(GameTestHelper h,List<BlockPos> cells){
        for(BlockPos cell:cells)h.assertTrue(h.getLevel().getBlockState(cell).isAir(),"unsupported or destroyed stack leaves no root/proxy at "+cell);
    }
    private static void clear(GameTestHelper h){
        BlockPos origin=h.absolutePos(ORIGIN);
        for(int y=6;y>=-1;y--)for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)
            h.getLevel().setBlock(origin.offset(x,y,z),Blocks.AIR.defaultBlockState(),18);
        for(ItemEntity entity:h.getLevel().getEntitiesOfClass(ItemEntity.class,area(h)))entity.discard();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void floor_printed_two_and_three_high_items_all_facings(GameTestHelper h){
        ServerPlayer owner=actor(h);
        for(Direction facing:Direction.Plane.HORIZONTAL)for(int height:List.of(2,3)){
            List<InfoTile> units=new ArrayList<>();InfoTile first=grounded(h,owner,facing);units.add(first);
            first.panel=first.panel.withPoster("mycinema:textures/gui/panel_white.png");first.screenMode=ScreenMode.INFORMATION;
            for(int i=1;i<height;i++)units.add(above(h,owner,units.get(i-1),units.get(i-1).getBlockPos()));
            h.assertTrue(owner.getMainHandItem().getCount()==16-height&&units.stream().map(t->t.identity).distinct().count()==height,
                    "two/three independent units preserve distinct identities and exact total item consumption");
            for(InfoTile unit:units){
                AdvertisingLifecycle.validateLoaded(unit);
                h.assertTrue(h.getLevel().getBlockEntity(unit.getBlockPos())==unit,"every separate root survives transitive ground validation");
            }
            h.assertTrue(AdvertisingAssembly.inspect(first)==null,"different source content stays independent instead of being discarded by automatic assembly");
            clear(h);
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void floor_explicit_assembly_bottom_middle_top_all_facings(GameTestHelper h){
        ServerPlayer owner=actor(h);
        for(Direction facing:Direction.Plane.HORIZONTAL)for(int height:List.of(2,3))for(int anchor=0;anchor<height;anchor++){
            List<InfoTile> units=stack(h,owner,facing,height);
            InfoTile root=assemble(h,owner,units,anchor);
            List<BlockPos> cells=root.advertising.cells(root.getBlockPos(),facing);
            h.getLevel().destroyBlock(root.getBlockPos(),true);
            gone(h,cells);
            h.assertTrue(dropped(h)==height,"destroying any selected assembly anchor spawns exactly the original unit count");
            clear(h);
        }
        h.succeed();
    }

    private static void destruction(GameTestHelper h,String mode){
        ServerPlayer owner=actor(h);List<InfoTile> units=stack(h,owner,Direction.NORTH,3);
        List<BlockPos> cells=units.stream().map(InfoTile::getBlockPos).toList();
        BlockPos bottom=cells.get(0);UUID surviving=units.get(0).identity;
        BlockPos target=switch(mode){case "middle"->cells.get(1);case "ground"->bottom.below();default->bottom;};
        if(mode.equals("proxy")){
            InfoTile root=assemble(h,owner,units,1);target=cells.get(2);
            h.assertTrue(h.getLevel().getBlockEntity(target) instanceof AdvertisingPartTile,"destruction targets a reciprocal proxy above a signed middle anchor");
            parts(h,root);
        }
        h.getLevel().destroyBlock(target,true);
        h.runAfterDelay(25,()->{
            boolean middle=mode.equals("middle");
            gone(h,middle?cells.subList(1,3):cells);
            if(middle)h.assertTrue(root(h,bottom).identity.equals(surviving),"removing the middle preserves the grounded independent bottom unit");
            h.assertTrue(dropped(h)==(middle?2:3),"actual ItemEntity total is exact after destruction and periodic reconciliation: "+mode);
            h.runAfterDelay(20,()->{
                h.assertTrue(dropped(h)==(middle?2:3),"later reconciliation cannot duplicate survival drops: "+mode);
                h.succeed();
            });
        });
    }
    @GameTest(template="empty",timeoutTicks=70)
    public static void floor_bottom_break_cascades_exact_drops(GameTestHelper h){destruction(h,"bottom");}
    @GameTest(template="empty",timeoutTicks=70)
    public static void floor_middle_break_preserves_base_exact_drops(GameTestHelper h){destruction(h,"middle");}
    @GameTest(template="empty",timeoutTicks=70)
    public static void floor_ground_break_cascades_exact_drops(GameTestHelper h){destruction(h,"ground");}
    @GameTest(template="empty",timeoutTicks=70)
    public static void floor_signed_assembly_proxy_break_exact_drops(GameTestHelper h){destruction(h,"proxy");}

    @GameTest(template="empty",timeoutTicks=60)
    public static void floor_signed_top_proxy_placement_and_full_footprint_support(GameTestHelper h){
        ServerPlayer owner=actor(h);
        for(Direction facing:Direction.Plane.HORIZONTAL){
            InfoTile lower=grounded(h,owner,facing);Direction right=facing.getCounterClockWise();
            AdvertisingGeometry wide=new AdvertisingGeometry(-8,0,24,16);groundUnder(h,lower,wide);resize(h,owner,lower,wide);
            BlockPos source=lower.getBlockPos().relative(right,-1).above();
            h.assertTrue(h.getLevel().getBlockEntity(source) instanceof AdvertisingPartTile,"signed upper edge is supplied by a proxy");
            InfoTile upper=above(h,owner,lower,source);
            resize(h,owner,upper,new AdvertisingGeometry(0,0,24,8));
            h.assertTrue(AdvertisingLifecycle.supported(h.getLevel(),upper.getBlockPos(),upper.getBlockState(),upper.advertising,true),
                    "all three upper base cells are supported across the lower signed-width top edge");
            AdvertisingPartTile upperPart=(AdvertisingPartTile)h.getLevel().getBlockEntity(upper.getBlockPos().relative(right));
            InfoTile edited=upperPart.root();
            h.assertTrue(edited==upper&&!edited.identity.equals(lower.identity),"upper proxy editor resolves its own root rather than the supporting lower board");
            resize(h,owner,edited,edited.advertising.edge("top",1));
            resize(h,owner,edited,edited.advertising.edge("top",-1));
            CompoundTag before=upper.saveWithFullMetadata(h.getLevel().registryAccess());
            rejects(h,()->AdvertisingLifecycle.resize(upper,owner,PanelGroup.of(upper).signature(),new AdvertisingGeometry(0,0,25,8)),
                    "an upper base cannot overhang even by one eighth beyond the supported footprint");
            h.assertTrue(before.equals(upper.saveWithFullMetadata(h.getLevel().registryAccess())),"failed overhang edit preserves every upper field");
            BlockPos missingGround=lower.getBlockPos().relative(right).below();
            h.getLevel().setBlock(missingGround,Blocks.AIR.defaultBlockState(),18);
            h.assertFalse(AdvertisingLifecycle.supported(h.getLevel(),upper.getBlockPos(),upper.getBlockState(),upper.advertising,true),
                    "upper support traverses the whole lower footprint down to solid ground");
            rejectedPlacement(h,owner,upper.getBlockPos(),Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),
                    "a locally aligned top whose lower dependency lost distant ground cannot support another unit");
            rejects(h,()->AdvertisingLifecycle.resize(upper,owner,PanelGroup.of(upper).signature(),upper.advertising.edge("top",1)),
                    "editing an existing upper unit also requires the complete loaded grounded dependency graph");
            clear(h);
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=70)
    public static void floor_lower_shortening_invalidates_upper_support(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile lower=grounded(h,owner,Direction.NORTH);
        resize(h,owner,lower,new AdvertisingGeometry(0,0,8,16));
        InfoTile upper=above(h,owner,lower,lower.getBlockPos().above());
        UUID lowerIdentity=lower.identity;BlockPos upperPos=upper.getBlockPos();
        resize(h,owner,lower,new AdvertisingGeometry(0,0,8,15));
        AdvertisingLifecycle.validateLoaded(upper);
        h.runAfterDelay(25,()->{
            h.assertTrue(root(h,lower.getBlockPos()).identity.equals(lowerIdentity)&&lower.advertising.height()==15,
                    "shortening the lower board preserves its identity and requested geometry");
            h.assertTrue(h.getLevel().getBlockState(upperPos).isAir()&&dropped(h)==1,
                    "upper root loses support when the lower top falls one eighth below its base and drops exactly once");
            parts(h,lower);h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=70)
    public static void floor_lower_png_transition_removes_upper_once_preserves_shaped_base(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile lower=grounded(h,owner,Direction.NORTH);
        AdvertisingGeometry geometry=new AdvertisingGeometry(0,0,16,16);
        groundUnder(h,lower,geometry);resize(h,owner,lower,geometry);
        InfoTile upper=above(h,owner,lower,lower.getBlockPos().above());
        BlockPos upperPos=upper.getBlockPos();UUID lowerIdentity=lower.identity;
        h.assertTrue(AdvertisingLifecycle.supported(h.getLevel(),upperPos,upper.getBlockState(),upper.advertising,true),
                "upper independent unit starts supported by the complete rectangular lower top");
        shape(h,owner,lower);
        h.assertFalse(AdvertisingLifecycle.supported(h.getLevel(),upperPos,upper.getBlockState(),upper.advertising,true),
                "changing a live lower rectangle into a PNG silhouette invalidates its existing upper dependency");
        // No manual validation: registered root/proxy tickers must handle a shape-only support change.
        h.runAfterDelay(25,()->{
            h.assertTrue(h.getLevel().getBlockState(upperPos).isAir()&&dropped(h)==1,
                    "shape transition removes the unsupported upper unit and spawns exactly one actual item");
            h.assertTrue(root(h,lower.getBlockPos())==lower&&lower.identity.equals(lowerIdentity)
                            &&lower.advertising.equals(geometry)&&!lower.advertisingShape.rectangular(),
                    "grounded lower board keeps its identity, geometry and applied PNG profile");
            parts(h,lower);
            h.runAfterDelay(20,()->{
                h.assertTrue(h.getLevel().getBlockState(upperPos).isAir()&&dropped(h)==1,
                        "later lifecycle reconciliation cannot recreate the upper unit or duplicate its drop");
                h.assertTrue(root(h,lower.getBlockPos())==lower,"shaped lower identity remains live after further validation");
                parts(h,lower);h.succeed();
            });
        });
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void floor_rejects_partial_top_and_png_support_all_facings(GameTestHelper h){
        ServerPlayer owner=actor(h);
        for(Direction facing:Direction.Plane.HORIZONTAL){
            for(AdvertisingGeometry geometry:List.of(new AdvertisingGeometry(0,0,7,8),new AdvertisingGeometry(0,0,8,7),
                    new AdvertisingGeometry(0,0,9,8),new AdvertisingGeometry(0,0,8,9))){
                InfoTile lower=grounded(h,owner,facing);groundUnder(h,lower,geometry);resize(h,owner,lower,geometry);
                BlockPos source=lower.getBlockPos();
                if(geometry.width()==9)source=source.relative(facing.getCounterClockWise());
                if(geometry.height()==9)source=source.above();
                rejectedPlacement(h,owner,source,Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),
                        "a partially covered source width or non-aligned top cannot support a full new unit");
                clear(h);
            }
            InfoTile lower=grounded(h,owner,facing);shape(h,owner,lower);
            rejectedPlacement(h,owner,lower.getBlockPos(),Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),
                    "PNG silhouette is not a rectangular support surface for a new independent floor unit");
            clear(h);
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void floor_rejects_wrong_owner_material_facing_and_broken_sources(GameTestHelper h){
        ServerPlayer owner=actor(h),stranger=actor(h);InfoTile lower=grounded(h,owner,Direction.NORTH);
        BlockPos pos=lower.getBlockPos();nearby(stranger,pos,Direction.NORTH);
        rejectedPlacement(h,stranger,pos,Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),"another owner's board cannot lend floor support");
        BlockPos replaceable=pos.above();var grass=Blocks.SHORT_GRASS.defaultBlockState();
        h.getLevel().setBlock(replaceable,grass,18);
        CompoundTag lowerBefore=lower.saveWithFullMetadata(h.getLevel().registryAccess());
        stranger.setYRot(Direction.SOUTH.toYRot());
        stranger.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CinemaManager.AD_PRINTED_ITEM.get(),8));
        BlockHitResult grassHit=new BlockHitResult(replaceable.getCenter().add(0,.5,0),Direction.UP,replaceable,false);
        BlockPlaceContext replacement=new BlockPlaceContext(new UseOnContext(stranger,InteractionHand.MAIN_HAND,grassHit));
        h.assertTrue(replacement.replacingClickedOnBlock()&&replacement.getClickedPos().equals(replaceable),
                "short grass selects replacement of the clicked cell directly above another owner's floor board");
        owner.setYRot(Direction.SOUTH.toYRot());
        BlockPlaceContext ownerReplacement=new BlockPlaceContext(new UseOnContext(owner,InteractionHand.MAIN_HAND,grassHit));
        h.assertTrue(CinemaManager.AD_PRINTED.get().getStateForPlacement(ownerReplacement)!=null,
                "the identical replaceable-cell geometry is admissible for the supporting board's owner");
        h.assertFalse(((BlockItem)CinemaManager.AD_PRINTED_ITEM.get()).place(replacement).consumesAction(),
                "replacing a plant above a foreign floor board cannot bypass supporting-owner authorization");
        h.assertTrue(stranger.getMainHandItem().getCount()==8&&h.getLevel().getBlockState(replaceable).equals(grass)
                        &&lowerBefore.equals(lower.saveWithFullMetadata(h.getLevel().registryAccess())),
                "rejected replacement preserves all items, the actual grass target and the supporting board NBT");
        h.getLevel().setBlock(replaceable,Blocks.AIR.defaultBlockState(),18);
        rejectedPlacement(h,owner,pos,Direction.UP,CinemaManager.AD_LED_ITEM.get(),"different material cannot borrow printed floor support");
        rejectedPlacement(h,owner,pos,Direction.DOWN,CinemaManager.AD_PRINTED_ITEM.get(),"floor DOWN attachment remains blocked");
        owner.setGameMode(GameType.ADVENTURE);
        rejectedPlacement(h,owner,pos,Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),"ordinary item-use protection still applies");
        owner.setGameMode(GameType.SURVIVAL);
        h.getLevel().setBlockAndUpdate(pos.above(),Blocks.GLASS.defaultBlockState());
        rejectedPlacement(h,owner,pos,Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),"occupied target cannot be overwritten");
        h.getLevel().setBlockAndUpdate(pos.above(),Blocks.AIR.defaultBlockState());
        var pig=EntityType.PIG.create(h.getLevel());pig.setPos(pos.getX()+.5,pos.getY()+1.05,pos.getZ()+.5);
        pig.setNoAi(true);h.getLevel().addFreshEntity(pig);
        rejectedPlacement(h,owner,pos,Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),"real BlockItem placement cannot insert the upper body inside a living entity");
        pig.discard();
        h.getLevel().removeBlockEntity(pos);
        rejectedPlacement(h,owner,pos,Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),"missing source tile cannot acquire a synthesized identity");
        h.assertTrue(h.getLevel().getChunkAt(pos).getBlockEntity(pos)==null,"rejection leaves missing source tile missing");h.getLevel().setBlockEntity(lower);
        resize(h,owner,lower,new AdvertisingGeometry(0,0,8,16));BlockPos proxy=pos.above();
        AdvertisingPartTile part=(AdvertisingPartTile)h.getLevel().getBlockEntity(proxy);
        UUID identity=part.rootIdentity;part.rootIdentity=UUID.randomUUID();
        rejectedPlacement(h,owner,proxy,Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),"stale proxy UUID cannot lend floor support");part.rootIdentity=identity;
        AdvertisingGeometry geometry=part.geometry;part.geometry=AdvertisingGeometry.DEFAULT;
        rejectedPlacement(h,owner,proxy,Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),"stale proxy geometry cannot lend floor support");part.geometry=geometry;
        AdvertisingShape profile=part.shape;part.shape=ringProfile();
        rejectedPlacement(h,owner,proxy,Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),"stale proxy profile cannot lend floor support");part.shape=profile;
        var state=part.getBlockState();h.getLevel().setBlock(proxy,state.setValue(AdvertisingBlock.FACING,Direction.SOUTH),18);
        rejectedPlacement(h,owner,proxy,Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),"wrong-facing proxy cannot lend floor support");h.getLevel().setBlock(proxy,state,18);
        h.getLevel().removeBlockEntity(proxy);
        rejectedPlacement(h,owner,proxy,Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),"missing proxy tile cannot lend floor support");
        h.assertTrue(h.getLevel().getChunkAt(proxy).getBlockEntity(proxy)==null,"rejection does not synthesize proxy tile");h.getLevel().setBlockEntity(part);
        h.getLevel().removeBlockEntity(pos);
        rejectedPlacement(h,owner,proxy,Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),"proxy without live root cannot lend floor support");
        h.assertTrue(h.getLevel().getChunkAt(pos).getBlockEntity(pos)==null,"proxy resolution does not synthesize missing owner root");h.getLevel().setBlockEntity(lower);
        BlockPos anchor=part.anchor;part.anchor=new BlockPos(100000,64,100000);
        h.assertFalse(h.getLevel().hasChunkAt(part.anchor),"remote fake proxy anchor starts unloaded");
        rejectedPlacement(h,owner,proxy,Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),"unloaded proxy owner cannot support placement");
        h.assertFalse(h.getLevel().hasChunkAt(part.anchor),"contextual rejection never force-loads proxy owner chunk");part.anchor=anchor;
        parts(h,lower);InfoTile upper=above(h,owner,lower,proxy);
        part.anchor=new BlockPos(100000,64,100000);
        h.assertFalse(AdvertisingLifecycle.supported(h.getLevel(),upper.getBlockPos(),upper.getBlockState(),upper.advertising,true),
                "placement and geometry edits cannot rely on an unloaded supporting proxy owner");
        h.assertTrue(AdvertisingLifecycle.supported(h.getLevel(),upper.getBlockPos(),upper.getBlockState(),upper.advertising,false),
                "an existing upper root defers an unknown supporting owner chunk");
        AdvertisingLifecycle.validateLoaded(upper);
        h.assertTrue(h.getLevel().getBlockEntity(upper.getBlockPos())==upper,"loaded validation retains an existing root while lower owner chunk is unknown");
        rejectedPlacement(h,owner,upper.getBlockPos(),Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),
                "new placement cannot build through an unknown transitive supporting owner");
        h.assertFalse(h.getLevel().hasChunkAt(part.anchor),"dependency validation never force-loads the missing owner chunk");part.anchor=anchor;
        var upperState=upper.getBlockState();h.getLevel().setBlock(upper.getBlockPos(),upperState.setValue(AdvertisingBlock.FACING,Direction.SOUTH),18);
        h.assertFalse(AdvertisingLifecycle.supported(h.getLevel(),upper.getBlockPos(),upper.getBlockState(),upper.advertising,true),
                "existing upper root must face the same direction as its lower support board");
        h.getLevel().setBlock(upper.getBlockPos(),upperState,18);
        UUID ownerId=upper.owner;upper.owner=stranger.getUUID();
        h.assertFalse(AdvertisingLifecycle.supported(h.getLevel(),upper.getBlockPos(),upper.getBlockState(),upper.advertising,true),
                "existing upper root must share its supporting board owner's UUID");upper.owner=ownerId;
        upper.owner=null;
        h.assertFalse(AdvertisingLifecycle.supported(h.getLevel(),upper.getBlockPos(),upper.getBlockState(),upper.advertising,true),
                "an existing root without ownership cannot adopt its supporting board's owner");upper.owner=ownerId;
        AdvertisingLifecycle.validateLoaded(upper);parts(h,lower);h.succeed();
    }

    private static AdvertisingShape ringProfile(){
        int[] rows=new int[32];for(int y=0;y<32;y++)rows[y]=y<8||y>=24?-1:0xff0000ff;
        String hash="b".repeat(64);
        return AdvertisingShape.fromAsset(AssetReference.ofHash(hash),new AssetStore.Metadata(hash,100,"PNG",32,32,Set.of(UUID.randomUUID()),AssetAlphaMask.ofRows(rows)));
    }
    private static void shape(GameTestHelper h,ServerPlayer owner,InfoTile root){
        nearby(owner,root.getBlockPos(),root.getBlockState().getValue(AdvertisingBlock.FACING));
        AdvertisingLifecycle.replaceUsing(root,owner,PanelGroup.of(root).signature(),root.advertising,ringProfile(),null,
                (pos,state)->h.getLevel().setBlock(pos,state,18));
        h.assertFalse(root.advertisingShape.rectangular(),"registered server PNG mask becomes the actual floor-board silhouette");parts(h,root);
    }
    /** Decode actual chunk NBT, discard live instances, and install fresh decoded instances top first. */
    private static Map<BlockPos,BlockEntity> coldRestore(GameTestHelper h,List<BlockPos> cells){
        var world=h.getLevel();Map<ChunkPos,ProtoChunk> chunks=new HashMap<>();Map<BlockPos,BlockEntity> restored=new LinkedHashMap<>();
        for(BlockPos cell:cells.stream().sorted(Comparator.comparingInt((BlockPos cell) -> cell.getY()).reversed()).toList()){
            h.assertTrue(world.hasChunkAt(cell),"fixture chunk is already loaded before serialization");
            ProtoChunk chunk=chunks.computeIfAbsent(new ChunkPos(cell),key->{
                CompoundTag data=ChunkSerializer.write(world,world.getChunkAt(cell));
                ProtoChunk decoded=ChunkSerializer.read(world,world.getPoiManager(),new RegionStorageInfo("mycinema-floor-stack-test",world.dimension(),"chunk"),key,data);
                if(decoded instanceof ImposterProtoChunk imposter)imposter.getWrapped().runPostLoad();return decoded;
            });
            CompoundTag nbt=chunk.getBlockEntityNbtForSaving(cell,world.registryAccess());
            h.assertTrue(nbt!=null&&chunk.getBlockState(cell).equals(world.getBlockState(cell)),"native chunk codec preserves state and registered tile NBT");
            BlockEntity tile=BlockEntity.loadStatic(cell,chunk.getBlockState(cell),nbt,world.registryAccess());
            h.assertTrue(tile!=null&&tile!=world.getBlockEntity(cell),"cold codec constructs a new tile instance");restored.put(cell,tile);
        }
        for(BlockPos cell:cells)world.removeBlockEntity(cell);
        for(BlockEntity tile:restored.values())world.setBlockEntity(tile);
        return restored;
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void floor_native_chunk_cold_restore_and_assembled_png_remain_grounded(GameTestHelper h){
        ServerPlayer owner=actor(h);
        for(boolean assembled:List.of(false,true)){
            List<InfoTile> units=stack(h,owner,Direction.EAST,3);InfoTile selected=units.get(2);
            if(assembled){selected=assemble(h,owner,units,2);shape(h,owner,selected);}
            List<BlockPos> cells=units.stream().map(InfoTile::getBlockPos).toList();
            Map<BlockPos,CompoundTag> before=new HashMap<>();
            for(BlockPos cell:cells)before.put(cell,h.getLevel().getBlockEntity(cell).saveWithFullMetadata(h.getLevel().registryAccess()));
            UUID identity=selected.identity;AdvertisingGeometry geometry=selected.advertising;AdvertisingShape profile=selected.advertisingShape;
            Map<BlockPos,BlockEntity> restored=coldRestore(h,cells);
            for(BlockPos cell:cells)h.assertTrue(before.get(cell).equals(restored.get(cell).saveWithFullMetadata(h.getLevel().registryAccess())),
                    "cold restore preserves exact identity, owner, item count, signed geometry and profile NBT");
            InfoTile root=(InfoTile)restored.get(selected.getBlockPos());
            h.assertTrue(root.identity.equals(identity)&&root.advertising.equals(geometry)&&root.advertisingShape.equals(profile)
                    &&root.assembledUnits==(assembled?3:1),"top-anchor root fields survive native chunk reconstruction");
            for(BlockEntity tile:restored.values())if(tile instanceof InfoTile info){
                AdvertisingLifecycle.validateLoaded(info);h.assertTrue(h.getLevel().getBlockEntity(info.getBlockPos())==info,"cold roots reconnect transitively to actual loaded ground");
            }
            if(assembled){
                parts(h,root);
                h.assertTrue(h.getLevel().noCollision(AdvertisingGeometry.worldBox(root.getBlockPos(),Direction.EAST,.4,-.6,.45,.6,-.4,.55)),
                        "assembled PNG ring keeps its transparent center passable after cold reload");
                h.assertFalse(h.getLevel().noCollision(AdvertisingGeometry.worldBox(root.getBlockPos(),Direction.EAST,.2,-1.98,.7,.8,-1.9,.9)),
                        "signed lower base remains physical even when the only root is at the stack top");
                nearby(owner,root.getBlockPos(),Direction.EAST);
                rejectedPlacement(h,owner,root.getBlockPos(),Direction.UP,CinemaManager.AD_PRINTED_ITEM.get(),
                        "completed and cold-restored PNG assembly cannot support another separate upper unit");
            }
            h.getLevel().destroyBlock(cells.get(0),true);
            for(BlockEntity tile:restored.values())if(tile instanceof InfoTile info)AdvertisingLifecycle.validateLoaded(info);
            gone(h,cells);
            h.assertTrue(dropped(h)==3,"cold independent stack or assembled PNG returns exactly its three original printed items");
            clear(h);
        }
        h.succeed();
    }
}
