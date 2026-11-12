package local.cinemamanager.seats;

import com.mojang.authlib.GameProfile;
import local.cinemamanager.*;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.CinemaDisplays;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

/** Real world, registered items and server protocol; no occupancy or furniture behavior is simulated. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class SeatGameTests {
    private static ServerPlayer actor(GameTestHelper h,String name){ServerPlayer p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),name));p.setGameMode(GameType.SURVIVAL);p.setPos(h.absolutePos(new BlockPos(3,2,2)).getCenter());return p;}
    private static void reject(GameTestHelper h,Runnable operation,String message){boolean rejected=false;try{operation.run();}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,message);}
    private static UseOnContext click(ServerPlayer player,BlockPos pos,Direction face){return new UseOnContext(player,InteractionHand.MAIN_HAND,new BlockHitResult(pos.getCenter(),face,pos,false));}
    private static void send(ServerPlayer player,ManagerTile manager,String action,SeatNetwork.Args args){SeatNetwork.apply(player,new SeatNetwork.Request(manager.getBlockPos(),manager.identity.toString(),UUID.randomUUID().toString(),action,SeatRegistry.JSON.toJson(args)));}

    @GameTest(template="empty",timeoutTicks=50)
    public static void seat_index_current_owner_revisions_and_saved_identity(GameTestHelper h){
        ServerPlayer owner=actor(h,"SeatOwnerQA"),stranger=actor(h,"SeatStrangerQA");
        BlockPos relative=new BlockPos(1,1,1);h.setBlock(relative,CinemaManager.MANAGER.get());ManagerTile manager=h.getBlockEntity(relative);manager.initializePlacement(owner.getUUID());
        h.setBlock(new BlockPos(4,1,1),CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile terminal=h.getBlockEntity(new BlockPos(4,1,1));terminal.owner(owner.getUUID());var hall=manager.enroll(owner,terminal,true);
        ItemStack binder=new ItemStack(SeatsModule.BINDER.get());owner.setItemInHand(InteractionHand.MAIN_HAND,binder);
        h.assertTrue(((SeatBindingItem)binder.getItem()).onItemUseFirst(binder,click(owner,manager.getBlockPos(),Direction.NORTH)).consumesAction(),"binding precedes manager's own interaction");
        h.assertTrue(SeatBindingItem.boundTo(owner,manager),"actual binding card records manager and dimension identity");
        BlockPos seatPos=h.absolutePos(new BlockPos(2,1,4));h.getLevel().setBlockAndUpdate(seatPos,Blocks.OAK_STAIRS.defaultBlockState());
        SeatNetwork.Args create=new SeatNetwork.Args();create.revision=0L;create.hall=hall.id;create.zone="Lower";create.row=1;create.number=1;create.target=true;create.position=seatPos.asLong();send(owner,manager,"save",create);
        SeatStorage store=SeatStorage.get(h.getLevel(),manager.identity);SeatRegistry registry=store.registry();
        h.assertTrue(registry.seats().size()==1&&registry.revision()==1,"owner creates exactly one manual index through server protocol");SeatRegistry.Seat saved=registry.seats().getFirst();
        h.assertTrue(saved.block().equals("minecraft:oak_stairs")&&!saved.verifiedIdentity()&&SeatBindings.status(h.getLevel(),saved).equals("unverified"),"legacy block fingerprint remains explicitly unverified physical identity");
        stranger.setItemInHand(InteractionHand.MAIN_HAND,binder.copy());send(stranger,manager,"save",create);
        h.assertTrue(registry.revision()==1&&!SeatNetwork.allowed(stranger,manager),"copied binding metadata never grants ownership");
        h.runAfterDelay(3,()->{SeatNetwork.Args stale=new SeatNetwork.Args();stale.revision=0L;stale.id=saved.id();stale.hall=hall.id;stale.zone="Lower";stale.row=1;stale.number=2;send(owner,manager,"save",stale);h.assertTrue(registry.revision()==1&&registry.find(saved.id()).number()==1,"stale editor cannot renumber a seat");});
        h.runAfterDelay(6,()->{create.revision=1L;create.number=2;send(owner,manager,"save",create);h.assertTrue(registry.revision()==1&&registry.seats().size()==1,"duplicate clicked position is rejected atomically");});
        h.runAfterDelay(9,()->{
            SeatNetwork.Args edit=new SeatNetwork.Args();edit.revision=1L;edit.id=saved.id();edit.hall=hall.id;edit.zone="Lower";edit.row=1;edit.number=1;edit.status="MAINTENANCE";send(owner,manager,"save",edit);
            h.assertTrue(registry.revision()==2&&registry.find(saved.id()).status()==SeatRegistry.Status.MAINTENANCE,"current revision edits serviceability while preserving identity");
            SeatStorage restoredStore=SeatStorage.load(store.save(new CompoundTag(),h.getLevel().registryAccess()),h.getLevel().registryAccess());
            h.assertTrue(restoredStore.registry().seats().equals(registry.seats())&&restoredStore.registry().revision()==2,"SavedData round trip preserves IDs, type fingerprint, counts, and exact revision");
            CompoundTag managerNbt=manager.saveWithFullMetadata(h.getLevel().registryAccess());ManagerTile restored=new ManagerTile(manager.getBlockPos(),manager.getBlockState());restored.loadWithComponents(managerNbt,h.getLevel().registryAccess());h.getLevel().removeBlockEntity(manager.getBlockPos());h.getLevel().setBlockEntity(restored);
            h.assertTrue(restored.identity.equals(manager.identity)&&SeatStorage.get(h.getLevel(),restored.identity)==store,"manager block entity reload reuses the same persistent inventory");
            h.assertFalse(SeatNetwork.allowed(owner,manager),"detached former manager object is no longer current");
            restored.owner=stranger.getUUID();h.assertFalse(SeatNetwork.allowed(owner,restored),"old owner loses authority immediately");h.assertTrue(SeatNetwork.allowed(stranger,restored),"new current owner may manage the preserved inventory");h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void seat_guide_manual_placement_publication_and_loaded_diagnostics(GameTestHelper h){
        ServerPlayer owner=actor(h,"SeatGuideOwnerQA"),stranger=actor(h,"SeatGuideGuestQA");
        for(Direction facing:Direction.Plane.HORIZONTAL){
            GuidePreset expected=switch(facing){
                case NORTH->new GuidePreset("Hall 1","Upper",8,12,0,GuidePreset.Arrow.UP_RIGHT);
                case SOUTH->new GuidePreset("Hall 1","Upper",26,27,0,GuidePreset.Arrow.LEFT,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.PLAIN);
                case EAST->new GuidePreset("Hall 1","Upper",26,27,0,GuidePreset.Arrow.RIGHT,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.ROW,1,12);
                default->new GuidePreset("Hall 1","Upper",123,456,0,GuidePreset.Arrow.UP_RIGHT,GuidePreset.RowScheme.NUMERIC,GuidePreset.RowPrefix.ROW);
            };
            BlockPos position=h.absolutePos(new BlockPos(5,2,5)),support=position.relative(facing.getOpposite());h.getLevel().setBlockAndUpdate(support,Blocks.STONE.defaultBlockState());owner.setPos(position.getCenter().add(facing.getStepX()*2,0,facing.getStepZ()*2));
            ItemStack stack=new ItemStack(SeatsModule.GUIDE_ITEM.get(),3);owner.setItemInHand(InteractionHand.MAIN_HAND,stack);
            BlockHitResult hit=new BlockHitResult(support.getCenter().add(facing.getStepX()*.5,0,facing.getStepZ()*.5),facing,support,false);
            h.assertTrue(((BlockItem)stack.getItem()).place(new BlockPlaceContext(new UseOnContext(owner,InteractionHand.MAIN_HAND,hit))).consumesAction(),"manual tag item places on the builder's chosen face");
            SeatGuideTile tile=(SeatGuideTile)h.getLevel().getBlockEntity(position);h.assertTrue(tile.owner.equals(owner.getUUID())&&tile.getBlockState().getValue(SeatGuideBlock.FACING)==facing,"real item placement establishes owner and facing");
            tile.update(owner,0,expected);stranger.setPos(owner.position());reject(h,()->tile.update(stranger,1,GuidePreset.empty()),"other player cannot change selected row range or arrow");reject(h,()->tile.update(owner,0,GuidePreset.empty()),"old guide revision cannot overwrite selected preset");
            if(expected.rowScheme()==GuidePreset.RowScheme.LETTERS)h.assertTrue(tile.preset.rowPrefix()==GuidePreset.RowPrefix.PLAIN&&tile.preset.rowDisplayLabel().equals("Z-AA"),"letter presets expose no prefix on the world guide, including normalized older ROW choices");
            tile.copyTo(owner,1);GuidePreset copied=SeatRegistry.JSON.fromJson(stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getString("mycinema_guide"),GuidePreset.class);h.assertTrue(expected.equals(copied),"copy publishes the saved exact row range and manually selected arrow only to the held tag stack");
            CompoundTag nbt=tile.saveWithFullMetadata(h.getLevel().registryAccess());SeatGuideTile loaded=new SeatGuideTile(position,tile.getBlockState());loaded.loadWithComponents(nbt,h.getLevel().registryAccess());h.getLevel().removeBlockEntity(position);h.getLevel().setBlockEntity(loaded);
            h.assertTrue(loaded.preset.equals(expected)&&loaded.revision==1&&loaded.identity.equals(tile.identity),"guide disk round trip preserves selection and identity");reject(h,()->tile.copyTo(owner,1),"detached tag cannot publish into inventory");
            owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(SeatsModule.BINDER.get()));h.assertTrue(((SeatBindingItem)SeatsModule.BINDER.get()).onItemUseFirst(owner.getMainHandItem(),click(owner,position,facing)).consumesAction(),"seat tool reaches guide editor before block/default item interaction");
            h.getLevel().setBlockAndUpdate(position,Blocks.AIR.defaultBlockState());h.getLevel().setBlockAndUpdate(support,Blocks.AIR.defaultBlockState());
        }
        BlockPos indexed=h.absolutePos(new BlockPos(2,1,4));h.getLevel().setBlockAndUpdate(indexed,Blocks.OAK_STAIRS.defaultBlockState());SeatRegistry registry=new SeatRegistry();String hall=UUID.randomUUID().toString();SeatRegistry.Seat seat=registry.put(0,"",hall,"Upper",8,1,indexed.asLong(),SeatRegistry.Status.AVAILABLE,"minecraft:oak_stairs");
        h.assertTrue(SeatBindings.status(h.getLevel(),seat).equals("unverified")&&!seat.verifiedIdentity(),"legacy blocks are never silently adopted as actual chairs");
        h.getLevel().setBlockAndUpdate(indexed,Blocks.STONE.defaultBlockState());h.assertTrue(SeatBindings.status(h.getLevel(),seat).equals("changed"),"changed block type is diagnosed without adopting it as a seat");h.getLevel().setBlockAndUpdate(indexed,Blocks.AIR.defaultBlockState());h.assertTrue(SeatBindings.status(h.getLevel(),seat).equals("missing"),"missing selected block is diagnosed without deleting the manual registration");
        BlockPos unloaded=new BlockPos(20000000,64,20000000);h.assertFalse(h.getLevel().hasChunkAt(unloaded),"diagnostic fixture begins unloaded");SeatRegistry.Seat remote=registry.put(1,"",hall,"Upper",8,2,unloaded.asLong(),SeatRegistry.Status.DISABLED,"minecraft:oak_stairs");h.assertTrue(SeatBindings.status(h.getLevel(),remote).equals("unloaded")&&!h.getLevel().hasChunkAt(unloaded),"diagnostics never force-load a seat chunk");
        SeatGuideTile unloadedGuide=new SeatGuideTile(unloaded,SeatsModule.GUIDE.get().defaultBlockState());unloadedGuide.setLevel(h.getLevel());unloadedGuide.owner=owner.getUUID();reject(h,()->unloadedGuide.copyTo(owner,0),"unloaded or unplaced tag cannot publish a preset");h.assertFalse(h.getLevel().hasChunkAt(unloaded),"failed publication never loads a chunk");
        h.assertTrue(registry.counts().getFirst().total()==2&&registry.find(seat.id()).status()==SeatRegistry.Status.AVAILABLE,"physical diagnostics do not invent live occupancy or silently change manual status/counts");
        if(!ModList.get().isLoaded("chiselsandbits"))h.assertTrue(ChiselsBitsBridge.availability().equals("mycinema.seats.bits_missing"),"absent C&B leaves own-tag classes usable and yields explicit optional adapter notice");
        h.succeed();
    }
    @GameTest(template="empty",templateNamespace="mycinema_cb_backend",timeoutTicks=100)
    public static void seat_optional_chisels_bits_item_backend_and_absent_fallback(GameTestHelper h) throws ReflectiveOperationException {
        ServerPlayer builder=actor(h,"SeatBitsBuilderQA");GuidePreset preset=new GuidePreset("Hall 1","Upper",123,456,0,GuidePreset.Arrow.LEFT);
        reject(h,()->ChiselsBitsBridge.create(builder,preset),"survival generation is refused before touching optional C&B APIs");
        reject(h,()->ChiselsBitsBridge.requireResolution(8),"unsupported bit precision is rejected rather than cropping the raster");
        reject(h,()->new GuidePreset("","",1000,1000,0,GuidePreset.Arrow.NONE),"overlong numeric labels are rejected rather than truncated");
        reject(h,()->new GuidePreset("x".repeat(33),"",1,1,0,GuidePreset.Arrow.NONE),"overlong full captions are rejected rather than truncated");
        builder.setGameMode(GameType.CREATIVE);
        BlockPos tagPos=h.absolutePos(new BlockPos(2,1,2));h.getLevel().setBlockAndUpdate(tagPos,SeatsModule.GUIDE.get().defaultBlockState());SeatGuideTile guide=(SeatGuideTile)h.getLevel().getBlockEntity(tagPos);guide.initialize(builder.getUUID(),ItemStack.EMPTY);guide.update(builder,0,preset);
        CompoundTag saved=guide.saveWithFullMetadata(h.getLevel().registryAccess());SeatGuideTile restored=new SeatGuideTile(tagPos,guide.getBlockState());restored.loadWithComponents(saved,h.getLevel().registryAccess());h.getLevel().removeBlockEntity(tagPos);h.getLevel().setBlockEntity(restored);
        java.util.Map<BlockPos,net.minecraft.world.level.block.state.BlockState> before=new java.util.HashMap<>();for(int x=0;x<7;x++)for(int y=0;y<4;y++)for(int z=0;z<7;z++){BlockPos p=h.absolutePos(new BlockPos(x,y,z));before.put(p,h.getLevel().getBlockState(p));}
        int inventoryBefore=inventoryItems(builder);String available=ChiselsBitsBridge.availability();String generatedItem="none";int verifiedWhite=0,verifiedBlack=0,verifiedArrowMasks=0,verifiedDisplayPresets=0;
        if(!available.isEmpty()){
            h.assertTrue(available.equals(ModList.get().isLoaded("chiselsandbits")?"mycinema.seats.bits_version":"mycinema.seats.bits_missing"),"optional missing/version gate explains the unsupported backend");
            reject(h,()->ChiselsBitsBridge.create(builder,restored.preset),"unsupported backend produces no substitute item");
        }else{
            ItemStack generated=ChiselsBitsBridge.create(builder,restored.preset);generatedItem=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(generated.getItem()).toString();
            h.assertTrue(generated.getCount()==1&&net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(generated.getItem()).getNamespace().equals("chiselsandbits"),"exact installed backend produces one native C&B item");
            String api="mod.chiselsandbits.api.";Class<?> itemType=Class.forName(api+"item.multistate.IMultiStateItem");
            h.assertTrue(Class.forName(api+"item.chiseled.IChiseledBlockItem").isInstance(generated.getItem()),"generated stack is the real placeable C&B chiseled-block item");
            Object actual=itemType.getMethod("createItemStack",ItemStack.class).invoke(generated.getItem(),generated);
            int[] counts=assertNorthFacingBits(h,actual,preset);verifiedWhite=counts[0];verifiedBlack=counts[1];
            for(GuidePreset.Arrow arrow:GuidePreset.Arrow.values()){
                GuidePreset directional=new GuidePreset(preset.hall(),preset.zone(),preset.firstRow(),preset.lastRow(),preset.seat(),arrow);
                ItemStack directionalItem=ChiselsBitsBridge.create(builder,directional);
                Object directionalActual=itemType.getMethod("createItemStack",ItemStack.class).invoke(directionalItem.getItem(),directionalItem);
                assertNorthFacingBits(h,directionalActual,directional);verifiedArrowMasks++;
            }
            for(GuidePreset.RowScheme scheme:GuidePreset.RowScheme.values())for(GuidePreset.RowPrefix prefix:GuidePreset.RowPrefix.values()){
                for(int[] range:new int[][]{{1,1,0},{26,27,0},{702,999,0},{703,703,999}}){
                    GuidePreset display=new GuidePreset("Hall QA","Upper",range[0],range[1],range[2],GuidePreset.Arrow.LEFT,scheme,prefix);
                    // Replay the requested prefix in JSON too: older letter saves must normalize before rendering.
                    var savedDisplay=SeatRegistry.JSON.toJsonTree(display).getAsJsonObject();savedDisplay.addProperty("rowPrefix",prefix.name());
                    display=SeatRegistry.JSON.fromJson(savedDisplay,GuidePreset.class);
                    if(scheme==GuidePreset.RowScheme.LETTERS)h.assertTrue(display.rowPrefix()==GuidePreset.RowPrefix.PLAIN,"saved/wire letter prefixes always normalize to plain");
                    ItemStack label=ChiselsBitsBridge.create(builder,display);
                    Object actualLabel=itemType.getMethod("createItemStack",ItemStack.class).invoke(label.getItem(),label);
                    assertNorthFacingBits(h,actualLabel,display);
                    h.assertTrue(label.getHoverName().getString().equals("Hall QA Upper · "+display.displayLabel()+" "+display.arrowText()),"generated item name retains the exact display label, with no letter prefix");
                    verifiedDisplayPresets++;
                }
            }
            for(GuidePreset.RowScheme scheme:GuidePreset.RowScheme.values())for(GuidePreset.Arrow arrow:GuidePreset.Arrow.values()){
                GuidePreset display=new GuidePreset("Hall QA","Upper",26,27,0,arrow,scheme,GuidePreset.RowPrefix.ROW);
                ItemStack label=ChiselsBitsBridge.create(builder,display);
                assertNorthFacingBits(h,itemType.getMethod("createItemStack",ItemStack.class).invoke(label.getItem(),label),display);
                verifiedDisplayPresets++;
            }
            for(GuidePreset.RowScheme scheme:GuidePreset.RowScheme.values())for(GuidePreset.RowPrefix prefix:GuidePreset.RowPrefix.values())for(int lastSeat:new int[]{12,24}){
                GuidePreset aisle=new GuidePreset("Hall QA","Upper",1,8,0,GuidePreset.Arrow.LEFT,scheme,prefix,1,lastSeat);
                aisle=SeatRegistry.JSON.fromJson(SeatRegistry.JSON.toJson(aisle),GuidePreset.class);
                ItemStack label=ChiselsBitsBridge.create(builder,aisle);
                assertNorthFacingBits(h,itemType.getMethod("createItemStack",ItemStack.class).invoke(label.getItem(),label),aisle);
                h.assertTrue(label.getHoverName().getString().equals("Hall QA Upper · "+aisle.displayLabel()+" "+aisle.arrowText()),"native aisle item retains complete row and seat ranges");
                verifiedDisplayPresets++;
            }
            GuidePreset tooLarge=new GuidePreset("Hall QA","Upper",703,999,0,GuidePreset.Arrow.LEFT,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.ROW,100,999);
            h.assertTrue(tooLarge.displayLabel().equals("AAA-ALK · S100-999"),"normal text guide retains the full large entrance ranges");
            reject(h,()->ChiselsBitsBridge.create(builder,tooLarge),"C&B refuses oversized combined ranges rather than truncating or omitting one range");
            h.assertTrue(generated.getHoverName().getString().contains("R123-456")&&generated.getHoverName().getString().contains("Upper"),"numeric range and full zone caption survive in the item name");
        }
        for(var cell:before.entrySet())h.assertTrue(h.getLevel().getBlockState(cell.getKey()).equals(cell.getValue()),"generating a portable item never places or mutates a world block");
        h.assertTrue(inventoryItems(builder)==inventoryBefore&&restored.preset.equals(preset)&&restored.revision==1,"item factory leaves inventory and saved manual preset unchanged until explicit delivery");h.succeed();
        com.mojang.logging.LogUtils.getLogger().info("MYCINEMA_CB_BACKEND_PASS backend={} supported_version={} item={} white_bits={} black_bits={} world_unchanged=true inventory_unchanged=true preset_reloaded=true north_face_pixels={} arrow_masks={} display_presets={}",available.isEmpty()?"native-generated":available,ChiselsBitsBridge.SUPPORTED_VERSION,generatedItem,verifiedWhite,verifiedBlack,available.isEmpty()?256:0,verifiedArrowMasks,verifiedDisplayPresets);
    }

    @GameTest(template="empty",templateNamespace="mycinema_cb_backend",timeoutTicks=100)
    public static void seat_optional_chisels_bits_north_face_placement(GameTestHelper h) throws ReflectiveOperationException {
        if(!ChiselsBitsBridge.availability().isEmpty()){h.succeed();return;}
        ServerPlayer builder=actor(h,"SeatBitsPlacementQA");builder.setGameMode(GameType.CREATIVE);
        GuidePreset preset=new GuidePreset("Hall QA","Lower",1,3,0,GuidePreset.Arrow.LEFT);
        BlockPos position=h.absolutePos(new BlockPos(3,2,4)),support=position.south();
        h.getLevel().setBlockAndUpdate(support,Blocks.STONE.defaultBlockState());
        builder.setPos(position.getCenter().add(0,-1,-3));builder.setYRot(0);builder.setXRot(0);builder.setShiftKeyDown(false);
        ItemStack generated=ChiselsBitsBridge.create(builder,preset);builder.setItemInHand(InteractionHand.MAIN_HAND,generated);
        BlockHitResult hit=new BlockHitResult(support.getCenter().add(0,0,-.5),Direction.NORTH,support,false);
        h.assertTrue(((BlockItem)generated.getItem()).place(new BlockPlaceContext(new UseOnContext(builder,InteractionHand.MAIN_HAND,hit))).consumesAction(),"native C&B item places on the north face of the selected backing block");
        Object placed=h.getLevel().getBlockEntity(position);
        h.assertTrue(Class.forName("mod.chiselsandbits.api.multistate.accessor.IAreaAccessor").isInstance(placed),"real placement creates an accessible native C&B block entity");
        assertNorthFacingBits(h,placed,preset);
        h.assertTrue(h.getLevel().getBlockState(support).is(Blocks.STONE),"placement leaves the selected backing wall intact");
        h.succeed();
        com.mojang.logging.LogUtils.getLogger().info("MYCINEMA_CB_PLACEMENT_PASS supported_version={} front=north viewer_yaw=0 rows=1-3 arrow=LEFT face_pixels=256",ChiselsBitsBridge.SUPPORTED_VERSION);
    }

    /** Compare the actual item or placed block's bit coordinates, not just color totals. */
    private static int[] assertNorthFacingBits(GameTestHelper h,Object actual,GuidePreset preset) throws ReflectiveOperationException {
        String api="mod.chiselsandbits.api.";
        var entryClass=Class.forName(api+"multistate.accessor.IStateEntryInfo");var materialClass=Class.forName(api+"blockinformation.BlockInformation");
        var information=entryClass.getMethod("getBlockInformation");var state=materialClass.getMethod("blockState");var start=entryClass.getMethod("getStartPoint");var end=entryClass.getMethod("getEndPoint");
        boolean[][] expected=BitTagRaster.raster(preset),seen=new boolean[16][16];int white=0,black=0;
        try(java.util.stream.Stream<?> stream=(java.util.stream.Stream<?>)Class.forName(api+"multistate.accessor.IAreaAccessor").getMethod("stream").invoke(actual)){
            for(Object entry:stream.toList()){
                var block=(net.minecraft.world.level.block.state.BlockState)state.invoke(information.invoke(entry));if(block.isAir())continue;
                var first=(net.minecraft.world.phys.Vec3)start.invoke(entry);var last=(net.minecraft.world.phys.Vec3)end.invoke(entry);
                h.assertTrue(Math.abs(first.z-15.0/16)<1e-8&&Math.abs(last.z-1)<1e-8,"tag material stays exactly one bit thick at the south edge");
                int minX=bitBoundary(h,first.x),maxX=bitBoundary(h,last.x),minY=bitBoundary(h,first.y),maxY=bitBoundary(h,last.y);
                h.assertTrue(minX<maxX&&minY<maxY,"material entries have positive bit-aligned area");
                for(int bitY=minY;bitY<maxY;bitY++)for(int bitX=minX;bitX<maxX;bitX++){
                    // A north-side viewer looks south: screen-right is west (-X), up is +Y.
                    int row=15-bitY,column=15-bitX;
                    h.assertFalse(seen[row][column],"native accessor contains no overlapping face pixels");seen[row][column]=true;
                    h.assertTrue(block.is(expected[row][column]?Blocks.WHITE_CONCRETE:Blocks.BLACK_CONCRETE),"north-facing "+preset.arrow()+" pixel at column "+column+", row "+row+" retains the exact glyph and arrow orientation");
                    if(expected[row][column])white++;else black++;
                }
            }
        }
        for(int row=0;row<16;row++)for(int column=0;column<16;column++)h.assertTrue(seen[row][column],"native tag contains every expected face pixel");
        h.assertTrue(white+black==256,"native tag contains exactly 256 white/black material bits");
        return new int[]{white,black};
    }
    private static int bitBoundary(GameTestHelper h,double coordinate){int bit=(int)Math.round(coordinate*16);h.assertTrue(bit>=0&&bit<=16&&Math.abs(coordinate*16-bit)<1e-8,"native material bounds align to the 16-bit grid");return bit;}
    private static int inventoryItems(ServerPlayer actor){int result=0;for(int slot=0;slot<actor.getInventory().getContainerSize();slot++)result+=actor.getInventory().getItem(slot).getCount();return result;}

}
