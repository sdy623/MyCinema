package local.cinemamanager.advertising;

import com.mojang.authlib.GameProfile;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.InfoTile;
import local.cinemamanager.panels.PanelGroup;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.storage.*;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;

import java.util.*;

/** Real item/world lifecycle plus injected mid-transaction write failure; no production authority bypass. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class AdvertisingGameTests {
    private static ServerPlayer actor(GameTestHelper h){
        ServerPlayer actor=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),"AdvertisingQA"));
        actor.setGameMode(GameType.SURVIVAL);actor.setPos(h.absolutePos(new BlockPos(5,3,2)).getCenter());return actor;
    }
    private static InfoTile place(GameTestHelper h,ServerPlayer actor,BlockPos relative,Direction facing,boolean floor,boolean led){
        BlockPos pos=h.absolutePos(relative),support=pos.relative(floor?Direction.DOWN:facing.getOpposite());
        h.getLevel().setBlockAndUpdate(support,Blocks.STONE.defaultBlockState());
        Item item=led?CinemaManager.AD_LED_ITEM.get():CinemaManager.AD_PRINTED_ITEM.get();
        ItemStack stack=new ItemStack(item,8);actor.setItemInHand(InteractionHand.MAIN_HAND,stack);
        actor.setYRot(facing.getOpposite().toYRot());
        Direction clicked=floor?Direction.UP:facing;
        BlockHitResult hit=new BlockHitResult(support.getCenter().add(clicked.getStepX()*.5,clicked.getStepY()*.5,clicked.getStepZ()*.5),clicked,support,false);
        var context=new BlockPlaceContext(new UseOnContext(actor,InteractionHand.MAIN_HAND,hit));
        h.assertTrue(((BlockItem)item).place(context).consumesAction(),"real advertising item placement succeeds");
        h.assertTrue(h.getLevel().getBlockEntity(pos) instanceof InfoTile,"advertising root uses registered InfoTile");
        InfoTile root=(InfoTile)h.getLevel().getBlockEntity(pos);
        h.assertTrue(root.owner.equals(actor.getUUID())&&root.getBlockState().getValue(AdvertisingBlock.FACING)==facing
                &&root.getBlockState().getValue(AdvertisingBlock.FLOOR)==floor,"owner, floor mode and outward facing recorded by item placement");
        return root;
    }
    private static void support(GameTestHelper h,InfoTile root,AdvertisingGeometry geometry){
        var state=root.getBlockState();Direction facing=state.getValue(AdvertisingBlock.FACING);boolean floor=state.getValue(AdvertisingBlock.FLOOR);
        for(BlockPos cell:geometry.cells(root.getBlockPos(),facing)){
            if(floor&&cell.getY()!=root.getBlockPos().getY()+Math.floorDiv(geometry.bottom(),8))continue;
            h.getLevel().setBlockAndUpdate(cell.relative(floor?Direction.DOWN:facing.getOpposite()),Blocks.STONE.defaultBlockState());
        }
    }
    private static void nearby(ServerPlayer actor,InfoTile root){Direction facing=root.getBlockState().getValue(AdvertisingBlock.FACING);actor.setPos(root.getBlockPos().getCenter().add(facing.getStepX()*2,0,facing.getStepZ()*2));}
    private static void resize(GameTestHelper h,InfoTile root,ServerPlayer actor,AdvertisingGeometry geometry){nearby(actor,root);AdvertisingLifecycle.resize(root,actor,PanelGroup.of(root).signature(),geometry);h.assertTrue(root.advertising.equals(geometry),"accepted resize publishes exact eighth-unit geometry");}
    private static void rejects(GameTestHelper h,Runnable action,String reason){boolean denied=false;try{action.run();}catch(IllegalArgumentException expected){denied=true;}h.assertTrue(denied,reason);}
    private static void parts(GameTestHelper h,InfoTile root){
        Direction facing=root.getBlockState().getValue(AdvertisingBlock.FACING);
        for(BlockPos cell:root.advertising.cells(root.getBlockPos(),facing))if(!cell.equals(root.getBlockPos())){
            h.assertTrue(h.getLevel().getBlockEntity(cell) instanceof AdvertisingPartTile,"expanded cell has registered proxy tile");
            var part=(AdvertisingPartTile)h.getLevel().getBlockEntity(cell);
            h.assertTrue(part.root()==root&&part.matches(root)&&part.geometry.equals(root.advertising)&&part.shape.equals(root.advertisingShape),"every proxy has current identity, exact geometry/shape and reciprocal membership");
        }
    }
    private static void gone(GameTestHelper h,List<BlockPos> cells){for(BlockPos cell:cells)h.assertTrue(h.getLevel().getBlockState(cell).isAir(),"breaking support/root/proxy leaves no occupied or colliding member");}

    @GameTest(template="empty",timeoutTicks=50)
    public static void advertising_wall_floor_and_all_facing_items(GameTestHelper h){
        ServerPlayer owner=actor(h);
        for(Direction facing:Direction.Plane.HORIZONTAL)for(boolean floor:new boolean[]{false,true})for(boolean led:new boolean[]{false,true}){
            InfoTile root=place(h,owner,new BlockPos(5,3,5),facing,floor,led);
            h.assertTrue(((AdvertisingBlock)root.getBlockState().getBlock()).emissive()==led,"LED and ambient printed variants retain distinct render mode");
            AABB b=root.advertising.body(root.getBlockPos(),facing,floor);
            h.assertFalse(h.getLevel().noCollision(b.deflate(.01)),"thin physical body collides after real item placement");
            h.getLevel().setBlockAndUpdate(root.getBlockPos(),Blocks.AIR.defaultBlockState());
            h.getLevel().setBlockAndUpdate(root.getBlockPos().relative(floor?Direction.DOWN:facing.getOpposite()),Blocks.AIR.defaultBlockState());
        }
        h.succeed();
    }

    private static InteractionResult placeAgainst(GameTestHelper h,ServerPlayer actor,BlockPos source,Direction face,boolean led){
        Item item=led?CinemaManager.AD_LED_ITEM.get():CinemaManager.AD_PRINTED_ITEM.get();
        if(!actor.getMainHandItem().is(item))actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(item,8));
        actor.setShiftKeyDown(true);
        BlockHitResult hit=new BlockHitResult(source.getCenter().add(face.getStepX()*.5,face.getStepY()*.5,face.getStepZ()*.5),face,source,false);
        return ((BlockItem)item).place(new BlockPlaceContext(new UseOnContext(actor,InteractionHand.MAIN_HAND,hit)));
    }
    private static void rejectedPlacement(GameTestHelper h,ServerPlayer actor,BlockPos source,Direction face,boolean led,String reason){
        Item item=led?CinemaManager.AD_LED_ITEM.get():CinemaManager.AD_PRINTED_ITEM.get();
        actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(item,8));
        BlockPos target=source.relative(face);var before=h.getLevel().getBlockState(target);
        h.assertFalse(placeAgainst(h,actor,source,face,led).consumesAction(),reason);
        h.assertTrue(actor.getMainHandItem().getCount()==8&&h.getLevel().getBlockState(target).equals(before),"rejected contextual placement preserves target and item count");
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void advertising_vertical_wall_item_placement_all_facings_materials_and_proxy_edges(GameTestHelper h){
        ServerPlayer owner=actor(h),builder=actor(h);
        for(Direction facing:Direction.Plane.HORIZONTAL)for(boolean led:new boolean[]{false,true})for(Direction edge:List.of(Direction.UP,Direction.DOWN))for(boolean proxy:new boolean[]{false,true}){
            InfoTile root=place(h,owner,new BlockPos(5,4,5),facing,false,led);
            if(proxy){
                AdvertisingGeometry geometry=AdvertisingGeometry.DEFAULT.edge(edge==Direction.UP?"top":"bottom",8);
                support(h,root,geometry);resize(h,root,owner,geometry);
            }
            BlockPos source=proxy?root.getBlockPos().relative(edge):root.getBlockPos(),target=source.relative(edge);
            BlockPos backing=target.relative(facing.getOpposite());h.getLevel().setBlockAndUpdate(backing,Blocks.STONE.defaultBlockState());
            root.panel=root.panel.withPoster("mycinema:textures/gui/panel_white.png");root.screenMode=ScreenMode.INFORMATION;
            var parentBefore=root.saveWithFullMetadata(h.getLevel().registryAccess());
            nearby(builder,root);builder.setYRot(facing.toYRot());
            Item item=led?CinemaManager.AD_LED_ITEM.get():CinemaManager.AD_PRINTED_ITEM.get();builder.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(item,8));
            h.assertTrue(placeAgainst(h,builder,source,edge,led).consumesAction(),"top/bottom of a live matching wall root or proxy places through the real BlockItem path");
            h.assertTrue(builder.getMainHandItem().getCount()==7,"successful placement consumes exactly one unit");
            h.assertTrue(h.getLevel().getBlockEntity(target) instanceof InfoTile,"vertical neighbor remains an independent root");
            InfoTile added=(InfoTile)h.getLevel().getBlockEntity(target);
            h.assertTrue(added.getBlockState().getValue(AdvertisingBlock.FACING)==facing&&!added.getBlockState().getValue(AdvertisingBlock.FLOOR),"wall orientation comes from the selected sign rather than player yaw or vertical hit face");
            h.assertTrue(added.owner.equals(builder.getUUID())&&!added.identity.equals(root.identity)&&added.advertising.equals(AdvertisingGeometry.DEFAULT)
                    &&added.assembledUnits==1&&added.screenMode==ScreenMode.PICTURE&&added.panel.poster().isEmpty(),"placement creates fresh builder ownership, identity and content without copying or assembling the parent");
            h.assertTrue(parentBefore.equals(root.saveWithFullMetadata(h.getLevel().registryAccess())),"borrowing another owner's orientation leaves the parent unchanged");
            h.assertTrue(Block.getDrops(added.getBlockState(),h.getLevel(),target,added).stream().filter(stack->stack.is(item)).mapToInt(ItemStack::getCount).sum()==1,"new independent unit retains a one-item survival drop");
            h.getLevel().setBlockAndUpdate(target,Blocks.AIR.defaultBlockState());
            var occupied=root.advertising.cells(root.getBlockPos(),facing);h.getLevel().setBlockAndUpdate(root.getBlockPos(),Blocks.AIR.defaultBlockState());
            for(BlockPos cell:occupied)h.getLevel().setBlockAndUpdate(cell.relative(facing.getOpposite()),Blocks.AIR.defaultBlockState());
            h.getLevel().setBlockAndUpdate(backing,Blocks.AIR.defaultBlockState());
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void advertising_printed_vertical_neighbors_only_assemble_explicitly(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(5,4,5),Direction.NORTH,false,false);
        BlockPos above=root.getBlockPos().above();h.getLevel().setBlockAndUpdate(above.south(),Blocks.STONE.defaultBlockState());nearby(owner,root);
        h.assertTrue(placeAgainst(h,owner,root.getBlockPos(),Direction.UP,false).consumesAction(),"matching printed upper unit places normally");
        h.assertTrue(root.advertising.equals(AdvertisingGeometry.DEFAULT)&&root.assembledUnits==1&&h.getLevel().getBlockEntity(above) instanceof InfoTile,"new units remain separate before explicit assembly");
        var plan=AdvertisingAssembly.inspect(root);h.assertTrue(plan!=null&&plan.units().size()==2,"vertical printed neighbors offer the existing two-unit assembly plan");
        UUID identity=root.identity;AdvertisingAssembly.assemble(root,owner,plan.signature());
        h.assertTrue(root.identity.equals(identity)&&root.advertising.height()==16&&root.assembledUnits==2,"explicit assembly preserves the original identity and two consumed units");parts(h,root);
        h.assertTrue(Block.getDrops(root.getBlockState(),h.getLevel(),root.getBlockPos(),root).stream().filter(stack->stack.is(CinemaManager.AD_PRINTED_ITEM.get())).mapToInt(ItemStack::getCount).sum()==2,"assembled vertical printed sign returns both original units");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void advertising_vertical_wall_placement_rejects_invalid_context_without_consuming_items(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(5,4,5),Direction.NORTH,false,false);
        BlockPos pos=root.getBlockPos(),target=pos.above();nearby(owner,root);
        rejectedPlacement(h,owner,pos,Direction.UP,false,"an upper wall unit still needs its own backing");
        h.getLevel().setBlockAndUpdate(target.south(),Blocks.STONE.defaultBlockState());
        h.getLevel().setBlockAndUpdate(target,Blocks.GLASS.defaultBlockState());
        rejectedPlacement(h,owner,pos,Direction.UP,false,"occupied target cannot be overwritten");
        h.getLevel().setBlockAndUpdate(target,Blocks.AIR.defaultBlockState());
        rejectedPlacement(h,owner,pos,Direction.UP,true,"printed parent cannot orient an LED unit");
        owner.setGameMode(GameType.ADVENTURE);
        rejectedPlacement(h,owner,pos,Direction.UP,false,"contextual placement retains ordinary item-use protection");owner.setGameMode(GameType.SURVIVAL);
        h.getLevel().removeBlockEntity(pos);
        rejectedPlacement(h,owner,pos,Direction.UP,false,"root block without its tile is not a contextual parent");
        h.assertTrue(h.getLevel().getChunkAt(pos).getBlockEntity(pos)==null,"rejected placement does not synthesize a missing parent identity");
        h.getLevel().setBlockEntity(root);
        AdvertisingGeometry wider=new AdvertisingGeometry(0,0,16,8);support(h,root,wider);resize(h,root,owner,wider);
        BlockPos proxy=pos.west();var part=(AdvertisingPartTile)h.getLevel().getBlockEntity(proxy);
        h.getLevel().setBlockAndUpdate(proxy.above().south(),Blocks.STONE.defaultBlockState());
        rejectedPlacement(h,owner,proxy,Direction.UP,true,"proxy cannot borrow a different material's root");
        UUID identity=part.rootIdentity;part.rootIdentity=UUID.randomUUID();
        rejectedPlacement(h,owner,proxy,Direction.UP,false,"stale proxy UUID cannot lend wall orientation");part.rootIdentity=identity;
        var geometry=part.geometry;part.geometry=AdvertisingGeometry.DEFAULT;
        rejectedPlacement(h,owner,proxy,Direction.UP,false,"proxy with stale geometry cannot lend wall orientation");part.geometry=geometry;
        var shape=part.shape;part.shape=ringProfile();
        rejectedPlacement(h,owner,proxy,Direction.UP,false,"proxy with stale silhouette cannot lend wall orientation");part.shape=shape;
        h.getLevel().removeBlockEntity(proxy);
        rejectedPlacement(h,owner,proxy,Direction.UP,false,"proxy block without its tile is not a contextual parent");
        h.assertTrue(h.getLevel().getChunkAt(proxy).getBlockEntity(proxy)==null,"rejected placement does not synthesize a missing proxy");h.getLevel().setBlockEntity(part);
        h.getLevel().removeBlockEntity(pos);
        rejectedPlacement(h,owner,proxy,Direction.UP,false,"proxy without a live root cannot lend wall orientation");
        h.assertTrue(h.getLevel().getChunkAt(pos).getBlockEntity(pos)==null,"proxy resolution during rejected placement does not synthesize a missing root identity");h.getLevel().setBlockEntity(root);
        BlockPos oldAnchor=part.anchor;part.anchor=new BlockPos(100000,64,100000);
        h.assertFalse(h.getLevel().hasChunkAt(part.anchor),"proxy's distant fake root starts unloaded");
        rejectedPlacement(h,owner,proxy,Direction.UP,false,"unloaded proxy root is never queried or loaded");
        h.assertFalse(h.getLevel().hasChunkAt(part.anchor),"failed contextual placement does not load the fake root chunk");part.anchor=oldAnchor;
        parts(h,root);AdvertisingLifecycle.validateLoaded(root);
        h.assertTrue(h.getLevel().getBlockEntity(pos)==root,"rejections leave the valid restored parent and proxies intact");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void advertising_printed_vertical_size_regression_and_floor_attachment_rules(GameTestHelper h){
        ServerPlayer owner=actor(h);
        for(boolean floor:new boolean[]{false,true}){
            InfoTile root=place(h,owner,new BlockPos(7,4,5),Direction.NORTH,floor,false);
            support(h,root,new AdvertisingGeometry(0,0,8,17));
            for(int amount:List.of(1,8))resize(h,root,owner,root.advertising.edge("top",amount));
            h.assertTrue(root.advertising.height()==17,"printed top edits retain exact 2.125m height");parts(h,root);
            AABB upper=AdvertisingGeometry.worldBox(root.getBlockPos(),Direction.NORTH,.2,2.01,floor?.45:.89,.8,2.1,floor?.55:.99);
            h.assertFalse(h.getLevel().noCollision(upper),"printed body above two metres keeps collision");
            var occupied=root.advertising.cells(root.getBlockPos(),Direction.NORTH);h.getLevel().setBlockAndUpdate(root.getBlockPos(),Blocks.AIR.defaultBlockState());
            for(BlockPos cell:occupied)h.getLevel().setBlockAndUpdate(cell.relative(floor?Direction.DOWN:Direction.SOUTH),Blocks.AIR.defaultBlockState());
        }
        InfoTile floor=place(h,owner,new BlockPos(7,4,5),Direction.NORTH,true,false);
        var before=floor.saveWithFullMetadata(h.getLevel().registryAccess());
        for(int amount:List.of(1,8))rejects(h,()->AdvertisingLifecycle.resize(floor,owner,PanelGroup.of(floor).signature(),floor.advertising.edge("bottom",amount)),"floor bottom expansion cannot overwrite supporting ground");
        h.assertTrue(before.equals(floor.saveWithFullMetadata(h.getLevel().registryAccess())),"rejected bottom growth preserves printed floor geometry and identity");
        h.getLevel().setBlockAndUpdate(floor.getBlockPos().above().south(),Blocks.STONE.defaultBlockState());
        h.assertTrue(placeAgainst(h,owner,floor.getBlockPos(),Direction.UP,false).consumesAction(),"matching owned floor sign accepts an independent upright upper unit without wall backing");
        h.getLevel().setBlockAndUpdate(floor.getBlockPos().below(),Blocks.AIR.defaultBlockState());
        h.assertTrue(h.getLevel().getBlockState(floor.getBlockPos()).isAir(),"ordinary floor sign still depends on its base support");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void advertising_printed_wall_all_edges_and_vertical_alpha_resize(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(7,4,5),Direction.NORTH,false,false);
        AdvertisingGeometry expanded=new AdvertisingGeometry(-9,-9,26,26);support(h,root,expanded);
        for(String edge:List.of("left","right","bottom","top"))for(int amount:List.of(1,8))resize(h,root,owner,root.advertising.edge(edge,amount));
        h.assertTrue(root.advertising.equals(expanded),"printed wall supports both steps on every signed edge");parts(h,root);
        for(String edge:List.of("top","bottom","right","left"))for(int amount:List.of(8,1))resize(h,root,owner,root.advertising.edge(edge,-amount));
        h.assertTrue(root.advertising.equals(AdvertisingGeometry.DEFAULT),"printed wall shrinks every edge back to its original identity cell");
        AdvertisingGeometry square=new AdvertisingGeometry(0,0,24,24);support(h,root,square);resize(h,root,owner,square);ring(h,root,owner);
        for(int amount:List.of(1,8)){
            AdvertisingGeometry taller=root.advertising.edge("top",amount);support(h,root,taller);resize(h,root,owner,taller);
            h.assertTrue(root.advertisingShape.equals(ringProfile()),"vertical resizing retains approved silhouette geometry");parts(h,root);
            var fit=root.advertisingShape.imageRect(taller);double middleY=(fit.bottom()+fit.top())/2;
            h.assertTrue(h.getLevel().noCollision(AdvertisingGeometry.worldBox(root.getBlockPos(),Direction.NORTH,1.3,middleY-.1,.9,1.7,middleY+.1,.98)),"transparent ring center stays passable after vertical fractional/whole growth");
            h.assertFalse(h.getLevel().noCollision(AdvertisingGeometry.worldBox(root.getBlockPos(),Direction.NORTH,1.3,fit.top()-.03,.9,1.7,fit.top()-.01,.98)),"opaque upper ring keeps collision above the original height");
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void advertising_wall_signed_edges_shrink_and_saved_links(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(5,3,5),Direction.NORTH,false,true);
        AdvertisingGeometry finalGeometry=new AdvertisingGeometry(-9,-9,26,26);support(h,root,finalGeometry);
        for(String edge:List.of("left","right","bottom","top"))for(int amount:List.of(1,8))resize(h,root,owner,root.advertising.edge(edge,amount));
        parts(h,root);h.assertTrue(root.advertising.equals(finalGeometry),"each edge supports both 1/8m and 1m growth");
        CompoundTag saved=root.saveWithFullMetadata(h.getLevel().registryAccess());String oldSignature=PanelGroup.of(root).signature();InfoTile detached=root;
        InfoTile restored=new InfoTile(root.getBlockPos(),root.getBlockState());restored.loadWithComponents(saved,h.getLevel().registryAccess());
        h.getLevel().removeBlockEntity(root.getBlockPos());h.getLevel().setBlockEntity(restored);root=restored;
        for(BlockPos pos:root.advertising.cells(root.getBlockPos(),Direction.NORTH))if(!pos.equals(root.getBlockPos())){
            AdvertisingPartTile original=(AdvertisingPartTile)h.getLevel().getBlockEntity(pos);
            CompoundTag tag=original.saveWithFullMetadata(h.getLevel().registryAccess());
            AdvertisingPartTile copy=new AdvertisingPartTile(pos,original.getBlockState());copy.loadWithComponents(tag,h.getLevel().registryAccess());
            h.getLevel().removeBlockEntity(pos);h.getLevel().setBlockEntity(copy);
        }
        AdvertisingLifecycle.validateLoaded(root);parts(h,root);
        h.assertTrue(root.identity.equals(detached.identity)&&root.owner.equals(owner.getUUID())&&PanelGroup.of(root).signature().equals(oldSignature),"disk restoration preserves identity, ownership, geometry and revision");
        rejects(h,()->AdvertisingLifecycle.resize(detached,owner,oldSignature,AdvertisingGeometry.DEFAULT),"detached old block-entity object cannot authorize a later edit");
        List<BlockPos> occupied=root.advertising.cells(root.getBlockPos(),Direction.NORTH);
        resize(h,root,owner,AdvertisingGeometry.DEFAULT);
        for(BlockPos pos:occupied)if(!pos.equals(root.getBlockPos()))h.assertTrue(h.getLevel().getBlockState(pos).isAir(),"shrinking releases every no-longer-intersected proxy");
        h.assertTrue(h.getLevel().getBlockEntity(root.getBlockPos())==root,"shrinking retains original root instance and identity");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void advertising_permissions_stale_occupied_and_missing_support_are_atomic(GameTestHelper h){
        ServerPlayer owner=actor(h),stranger=actor(h);InfoTile root=place(h,owner,new BlockPos(5,3,5),Direction.NORTH,false,false);
        AdvertisingGeometry wider=new AdvertisingGeometry(0,0,16,8);support(h,root,wider);nearby(owner,root);nearby(stranger,root);
        String token=PanelGroup.of(root).signature();long revision=root.panelRevision;BlockPos extension=root.getBlockPos().west();
        rejects(h,()->AdvertisingLifecycle.resize(root,stranger,token,wider),"another survival player's UUID cannot resize owner's advertisement");
        h.getLevel().setBlockAndUpdate(extension,Blocks.WATER.defaultBlockState());
        rejects(h,()->AdvertisingLifecycle.resize(root,owner,token,wider),"even replaceable occupied cells cannot be overwritten");
        h.assertTrue(h.getLevel().getBlockState(extension).is(Blocks.WATER),"rejected growth preserves foreign water instead of replacing it");
        h.getLevel().setBlockAndUpdate(extension,Blocks.AIR.defaultBlockState());
        h.getLevel().setBlockAndUpdate(extension.south(),Blocks.AIR.defaultBlockState());
        rejects(h,()->AdvertisingLifecycle.resize(root,owner,token,wider),"every new wall cell requires backing");
        h.assertTrue(root.advertising.equals(AdvertisingGeometry.DEFAULT)&&root.panelRevision==revision&&h.getLevel().getBlockState(extension).isAir(),"all preflight failures leave geometry, revision and reservations untouched");
        support(h,root,wider);resize(h,root,owner,wider);
        rejects(h,()->AdvertisingLifecycle.resize(root,owner,token,AdvertisingGeometry.DEFAULT),"old editor signature cannot overwrite resized display");
        owner.setPos(root.getBlockPos().getCenter().add(50,0,0));
        rejects(h,()->AdvertisingLifecycle.resize(root,owner,PanelGroup.of(root).signature(),AdvertisingGeometry.DEFAULT),"owner must remain within display reach");
        parts(h,root);h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void advertising_expansion_blocks_entities_only_in_actual_new_body(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(5,3,5),Direction.NORTH,false,true);
        AdvertisingGeometry wider=new AdvertisingGeometry(0,0,16,8);support(h,root,wider);nearby(owner,root);
        BlockPos cell=root.getBlockPos().west();var pig=EntityType.PIG.create(h.getLevel());
        pig.setPos(cell.getX()+.5,cell.getY()+.1,cell.getZ()+.9);pig.setNoAi(true);h.getLevel().addFreshEntity(pig);
        String token=PanelGroup.of(root).signature();
        rejects(h,()->AdvertisingLifecycle.resize(root,owner,token,wider),"new thin material cannot appear inside a living entity");
        h.assertTrue(h.getLevel().getBlockState(cell).isAir()&&root.advertising.equals(AdvertisingGeometry.DEFAULT),"entity rejection reserves no cells");
        pig.setPos(cell.getX()+.5,cell.getY()+.1,cell.getZ()+.25);
        resize(h,root,owner,wider);parts(h,root);
        h.assertTrue(h.getLevel().noCollision(new AABB(cell.getX()+.1,cell.getY()+.1,cell.getZ()+.1,cell.getX()+.9,cell.getY()+.9,cell.getZ()+.8)),"proxy does not act as a full-block invisible barrier in front of wall sign");
        pig.discard();h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void advertising_floor_foot_collision_and_support(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(5,2,5),Direction.NORTH,true,false);
        AdvertisingGeometry wider=new AdvertisingGeometry(0,0,16,8);support(h,root,wider);nearby(owner,root);
        BlockPos cell=root.getBlockPos().west();var pig=EntityType.PIG.create(h.getLevel());
        pig.setPos(cell.getX()+.5,cell.getY()+.05,cell.getZ()+1.2);pig.setNoAi(true);h.getLevel().addFreshEntity(pig);
        rejects(h,()->AdvertisingLifecycle.resize(root,owner,PanelGroup.of(root).signature(),wider),"base protrusion also checks entity clearance");
        pig.setPos(cell.getX()+.5,cell.getY()+.2,cell.getZ()+1.2);resize(h,root,owner,wider);pig.discard();
        h.assertTrue(h.getLevel().noCollision(new AABB(cell.getX()+.1,cell.getY()+.2,cell.getZ()+.7,cell.getX()+.9,cell.getY()+.9,cell.getZ()+.9)),"space above wide foot stays physically empty");
        List<BlockPos> cells=root.advertising.cells(root.getBlockPos(),Direction.NORTH);h.getLevel().setBlockAndUpdate(cell.below(),Blocks.AIR.defaultBlockState());
        h.runAfterDelay(2,()->{gone(h,cells);h.succeed();});
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void advertising_wall_proxy_backing_break_removes_whole_sign(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(5,3,5),Direction.NORTH,false,true);
        AdvertisingGeometry geometry=new AdvertisingGeometry(0,0,16,16);support(h,root,geometry);resize(h,root,owner,geometry);
        List<BlockPos> cells=geometry.cells(root.getBlockPos(),Direction.NORTH);h.getLevel().setBlockAndUpdate(root.getBlockPos().west().above().south(),Blocks.AIR.defaultBlockState());
        h.runAfterDelay(2,()->{gone(h,cells);h.succeed();});
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void advertising_breaking_root_or_proxy_cleans_owned_cells(GameTestHelper h){
        ServerPlayer owner=actor(h);
        for(boolean breakProxy:new boolean[]{false,true}){
            InfoTile root=place(h,owner,new BlockPos(5,3,5),Direction.NORTH,false,true);
            AdvertisingGeometry geometry=new AdvertisingGeometry(0,0,16,16);support(h,root,geometry);resize(h,root,owner,geometry);
            List<BlockPos> cells=geometry.cells(root.getBlockPos(),Direction.NORTH);
            h.getLevel().destroyBlock(breakProxy?root.getBlockPos().west().above():root.getBlockPos(),true);gone(h,cells);
        }
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void advertising_partial_placement_and_removal_failures_restore_every_snapshot(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(7,3,5),Direction.NORTH,false,true);
        AdvertisingGeometry small=new AdvertisingGeometry(0,0,16,8),large=new AdvertisingGeometry(0,0,32,8);
        support(h,root,large);resize(h,root,owner,small);
        CompoundTag rootBefore=root.saveWithFullMetadata(h.getLevel().registryAccess());
        CompoundTag partBefore=h.getLevel().getBlockEntity(root.getBlockPos().west()).saveWithFullMetadata(h.getLevel().registryAccess());
        String token=PanelGroup.of(root).signature();int[] writes={0};
        rejects(h,()->AdvertisingLifecycle.resizeUsing(root,owner,token,large,(pos,state)->++writes[0]!=2&&h.getLevel().setBlock(pos,state,18)),"failure after first new proxy rolls back entire expansion");
        h.assertTrue(writes[0]==2&&rootBefore.equals(root.saveWithFullMetadata(h.getLevel().registryAccess()))
                &&partBefore.equals(h.getLevel().getBlockEntity(root.getBlockPos().west()).saveWithFullMetadata(h.getLevel().registryAccess())),"rollback restores exact root and prior proxy NBT including revision and identity");
        h.assertTrue(h.getLevel().getBlockState(root.getBlockPos().west(2)).isAir()&&h.getLevel().getBlockState(root.getBlockPos().west(3)).isAir(),"rollback removes successful partial writes");parts(h,root);
        resize(h,root,owner,large);CompoundTag largeBefore=root.saveWithFullMetadata(h.getLevel().registryAccess());String largeToken=PanelGroup.of(root).signature();writes[0]=0;
        rejects(h,()->AdvertisingLifecycle.resizeUsing(root,owner,largeToken,AdvertisingGeometry.DEFAULT,(pos,state)->++writes[0]!=2&&h.getLevel().setBlock(pos,state,18)),"failure after first removed proxy restores complete previous surface");
        h.assertTrue(largeBefore.equals(root.saveWithFullMetadata(h.getLevel().registryAccess()))&&PanelGroup.of(root).signature().equals(largeToken),"removal rollback restores geometry and editor signature");parts(h,root);
        h.assertFalse(AdvertisingLifecycle.mutating(h.getLevel(),root.getBlockPos()),"failure never leaks the recursion/mutation guard");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void advertising_orphans_wait_for_loaded_owner_and_never_hijack_new_identity(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(5,3,5),Direction.NORTH,false,false);
        BlockPos orphanPos=root.getBlockPos().west(),far=new BlockPos(100000,64,100000);
        h.getLevel().setBlock(orphanPos,CinemaManager.AD_PART.get().defaultBlockState(),18);
        AdvertisingPartTile orphan=(AdvertisingPartTile)h.getLevel().getBlockEntity(orphanPos);orphan.anchor=far;orphan.rootIdentity=UUID.randomUUID();
        h.assertFalse(h.getLevel().hasChunkAt(far),"far owner chunk starts unloaded");orphan.reconcile();
        h.assertTrue(h.getLevel().getBlockEntity(orphanPos)==orphan&&orphan.root()==null,"proxy defers cleanup while owner chunk is unavailable");
        h.assertFalse(h.getLevel().hasChunkAt(far),"lookup and orphan reconciliation never force-load owner chunk");
        orphan.anchor=root.getBlockPos();orphan.rootIdentity=UUID.randomUUID();orphan.reconcile();
        h.assertTrue(h.getLevel().getBlockState(orphanPos).isAir()&&h.getLevel().getBlockEntity(root.getBlockPos())==root,"loaded stale identity removes only orphan, preserving replacement owner");
        h.getLevel().setBlock(orphanPos,CinemaManager.AD_PART.get().defaultBlockState(),18);
        orphan=(AdvertisingPartTile)h.getLevel().getBlockEntity(orphanPos);orphan.link(root);orphan.reconcile();
        h.assertTrue(h.getLevel().getBlockState(orphanPos).isAir()&&h.getLevel().getBlockEntity(root.getBlockPos())==root,"same UUID outside current geometry cannot claim or destroy root");
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void advertising_tick_reconciles_loaded_orphan_and_missed_support_updates(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(5,3,5),Direction.NORTH,false,true);
        AdvertisingGeometry geometry=new AdvertisingGeometry(0,0,16,8);support(h,root,geometry);resize(h,root,owner,geometry);
        List<BlockPos> cells=geometry.cells(root.getBlockPos(),Direction.NORTH);
        // No neighbor/shape notification: restoration still catches a missing support on the next validation tick.
        h.getLevel().setBlock(root.getBlockPos().west().south(),Blocks.AIR.defaultBlockState(),18);
        BlockPos orphanPos=h.absolutePos(new BlockPos(10,3,5));
        h.getLevel().setBlock(orphanPos,CinemaManager.AD_PART.get().defaultBlockState(),18);
        AdvertisingPartTile orphan=(AdvertisingPartTile)h.getLevel().getBlockEntity(orphanPos);
        orphan.anchor=orphanPos.above();orphan.rootIdentity=UUID.randomUUID();
        h.runAfterDelay(25,()->{gone(h,cells);h.assertTrue(h.getLevel().getBlockState(orphanPos).isAir(),"registered proxy ticker removes a loaded orphan without a neighbor event");h.succeed();});
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void advertising_native_chunk_codec_retains_root_and_proxy_links(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(5,3,5),Direction.EAST,false,true);
        AdvertisingGeometry geometry=new AdvertisingGeometry(-1,-1,18,18);support(h,root,geometry);resize(h,root,owner,geometry);ring(h,root,owner);
        var world=h.getLevel();Map<ChunkPos,ProtoChunk> reloaded=new HashMap<>();
        for(BlockPos cell:geometry.cells(root.getBlockPos(),Direction.EAST)){
            h.assertTrue(world.hasChunkAt(cell),"fixture chunk is already loaded before explicit codec test");
            ChunkPos chunkPos=new ChunkPos(cell);
            ProtoChunk restored=reloaded.computeIfAbsent(chunkPos,key->{
                CompoundTag tag=ChunkSerializer.write(world,world.getChunkAt(cell));
                ProtoChunk loaded=ChunkSerializer.read(world,world.getPoiManager(),new RegionStorageInfo("mycinema-advertising-test",world.dimension(),"chunk"),key,tag);
                if(loaded instanceof ImposterProtoChunk imposter)imposter.getWrapped().runPostLoad();return loaded;
            });
            CompoundTag nbt=restored.getBlockEntityNbtForSaving(cell,world.registryAccess());h.assertTrue(nbt!=null,"native chunk codec retains advertising tile NBT");
            BlockEntity tile=BlockEntity.loadStatic(cell,restored.getBlockState(cell),nbt,world.registryAccess());h.assertTrue(tile!=null,"registry reconstructs advertising tile after native chunk roundtrip");
            if(cell.equals(root.getBlockPos())){InfoTile restoredRoot=(InfoTile)tile;h.assertTrue(restoredRoot.identity.equals(root.identity)&&restoredRoot.owner.equals(owner.getUUID())&&restoredRoot.advertising.equals(geometry)&&restoredRoot.advertisingShape.equals(root.advertisingShape),"root identity, owner, signed geometry and PNG profile survive vanilla chunk codec");}
            else{AdvertisingPartTile part=(AdvertisingPartTile)tile;part.setLevel(world);h.assertTrue(part.matches(root)&&part.geometry.equals(geometry)&&part.shape.equals(root.advertisingShape)&&part.root()==root,"proxy anchor, root UUID, geometry and PNG profile survive native chunk codec and resolve loaded owner");}
        }
        h.succeed();
    }
    /** Transaction fixtures use the package-private writer seam; the public command still resolves server asset bytes. */
    private static AdvertisingShape ringProfile(){
        int[] rows=new int[32];for(int y=0;y<32;y++)rows[y]=y<8||y>=24?-1:0xff0000ff;
        String hash="a".repeat(64);
        return AdvertisingShape.fromAsset(local.cinemamanager.panels.assets.AssetReference.ofHash(hash),
                new local.cinemamanager.panels.assets.AssetStore.Metadata(hash,100,"PNG",32,32,Set.of(UUID.randomUUID()),local.cinemamanager.panels.assets.AssetAlphaMask.ofRows(rows)));
    }
    private static void ring(GameTestHelper h,InfoTile root,ServerPlayer actor){
        nearby(actor,root);AdvertisingLifecycle.replaceUsing(root,actor,PanelGroup.of(root).signature(),root.advertising,ringProfile(),null,(pos,state)->h.getLevel().setBlock(pos,state,18));parts(h,root);
    }
    @GameTest(template="empty",timeoutTicks=50)
    public static void advertising_png_hole_is_passable_but_floor_base_stays_physical(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(7,2,5),Direction.NORTH,true,false);
        AdvertisingGeometry g=new AdvertisingGeometry(0,0,24,24);support(h,root,g);resize(h,root,owner,g);ring(h,root,owner);
        BlockPos middle=root.getBlockPos().west().above();
        h.assertTrue(h.getLevel().noCollision(new AABB(middle.getX()+.1,middle.getY()+.1,middle.getZ()+.45,middle.getX()+.9,middle.getY()+.9,middle.getZ()+.55)),"clear center of PNG remains passable in actual world block collision");
        BlockPos foot=root.getBlockPos().west();
        h.assertFalse(h.getLevel().noCollision(new AABB(foot.getX()+.1,foot.getY()+.02,foot.getZ()+.7,foot.getX()+.9,foot.getY()+.1,foot.getZ()+.9)),"physical base remains even below transparent image center");
        for(BlockPos cell:g.cells(root.getBlockPos(),Direction.NORTH))if(!cell.equals(root.getBlockPos())){
            AdvertisingPartTile original=(AdvertisingPartTile)h.getLevel().getBlockEntity(cell);CompoundTag saved=original.saveWithFullMetadata(h.getLevel().registryAccess());
            AdvertisingPartTile loaded=new AdvertisingPartTile(cell,original.getBlockState());loaded.loadWithComponents(saved,h.getLevel().registryAccess());loaded.setLevel(h.getLevel());
            h.assertTrue(loaded.shape.equals(root.advertisingShape)&&loaded.root()==root,"per-cell profile and reciprocal identity survive reload");
        }
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=50)
    public static void advertising_png_rectangle_restore_checks_only_added_material_and_rolls_back_content(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(7,2,5),Direction.NORTH,true,false);
        AdvertisingGeometry g=new AdvertisingGeometry(0,0,24,24);support(h,root,g);resize(h,root,owner,g);ring(h,root,owner);
        var pig=EntityType.PIG.create(h.getLevel());pig.setPos(root.getBlockPos().getX()-.5,root.getBlockPos().getY()+1,root.getBlockPos().getZ()+.5);pig.setNoAi(true);h.getLevel().addFreshEntity(pig);
        CompoundTag before=root.saveWithFullMetadata(h.getLevel().registryAccess());int[] committed={0};
        rejects(h,()->AdvertisingLifecycle.replaceForPoster(root,owner,PanelGroup.of(root).signature(),"",()->committed[0]++),"changing image cannot restore solid rectangle around an entity in its old alpha hole");
        h.assertTrue(committed[0]==0&&before.equals(root.saveWithFullMetadata(h.getLevel().registryAccess())),"failed clearance preserves both old image profile and content without callback");pig.discard();
        rejects(h,()->AdvertisingLifecycle.replaceForPoster(root,owner,PanelGroup.of(root).signature(),"",()->{root.panelRevision+=9;root.scope=local.cinemamanager.panels.PanelScope.LOBBY;throw new IllegalStateException("injected content failure");}),"content failure after proxy relink restores exact prior root and parts");
        h.assertTrue(before.equals(root.saveWithFullMetadata(h.getLevel().registryAccess()))&&!AdvertisingLifecycle.mutating(h.getLevel(),root.getBlockPos()),"profile, content, revision and mutation guard roll back together");parts(h,root);
        AdvertisingLifecycle.replaceForPoster(root,owner,PanelGroup.of(root).signature(),"",()->committed[0]++);
        h.assertTrue(committed[0]==1&&root.advertisingShape.rectangular(),"clear rectangle and requested material commit together");parts(h,root);h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=50)
    public static void advertising_png_partial_resize_preserves_shape_and_stale_proxy_is_rejected(GameTestHelper h){
        ServerPlayer owner=actor(h);InfoTile root=place(h,owner,new BlockPos(7,3,5),Direction.NORTH,false,false);
        AdvertisingGeometry small=new AdvertisingGeometry(0,0,16,16),large=new AdvertisingGeometry(0,0,32,16);support(h,root,large);resize(h,root,owner,small);ring(h,root,owner);
        CompoundTag before=root.saveWithFullMetadata(h.getLevel().registryAccess());int[] writes={0};
        rejects(h,()->AdvertisingLifecycle.resizeUsing(root,owner,PanelGroup.of(root).signature(),large,(pos,state)->++writes[0]!=2&&h.getLevel().setBlock(pos,state,18)),"mid-resize failure rolls back silhouette proxy links");
        h.assertTrue(before.equals(root.saveWithFullMetadata(h.getLevel().registryAccess())),"rollback preserves exact saved profile");parts(h,root);
        AdvertisingPartTile part=(AdvertisingPartTile)h.getLevel().getBlockEntity(root.getBlockPos().west());part.shape=AdvertisingShape.RECTANGLE;
        h.assertTrue(part.root()==null,"matching root UUID with stale collision profile is not a valid proxy");
        rejects(h,()->AdvertisingLifecycle.resize(root,owner,PanelGroup.of(root).signature(),large),"stale profile proxy blocks mutation instead of being silently claimed");
        part.shape=root.advertisingShape;parts(h,root);
        rejects(h,()->AdvertisingLifecycle.applyImageShape(root,owner,PanelGroup.of(root).signature(),true),"an arbitrary fixture or remote reference cannot authorize public PNG shape command");
        h.succeed();
    }

}
