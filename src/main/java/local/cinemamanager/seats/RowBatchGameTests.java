package local.cinemamanager.seats;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import local.cinemamanager.*;
import local.cinemamanager.CinemaDisplays;
import local.cinemamanager.theater.CinemaTerminalTile;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.lang.reflect.Field;
import java.util.*;

/** Source acceptance tests for an independent QA world; never use an original project save. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class RowBatchGameTests {
    private record Fixture(ServerPlayer owner,ManagerTile manager,ItemStack card,String hall,SeatRegistry registry){}
    private record PendingView(String token,RowBatch.Preview preview){}
    private static ServerPlayer actor(GameTestHelper h,String name){ServerPlayer p=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),name));p.setGameMode(GameType.SURVIVAL);p.setPos(h.absolutePos(new BlockPos(3,2,2)).getCenter());return p;}
    private static void use(ServerPlayer actor,BlockPos pos){
        ItemStack card=actor.getMainHandItem();((SeatBindingItem)card.getItem()).onItemUseFirst(card,new UseOnContext(actor,InteractionHand.MAIN_HAND,new BlockHitResult(pos.getCenter(),Direction.UP,pos,false)));
    }
    private static Fixture fixture(GameTestHelper h){
        ServerPlayer owner=actor(h,"RowBatchOwner");h.setBlock(new BlockPos(1,1,1),CinemaManager.MANAGER.get());ManagerTile manager=h.getBlockEntity(new BlockPos(1,1,1));manager.initializePlacement(owner.getUUID());
        h.setBlock(new BlockPos(4,1,1),CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile terminal=h.getBlockEntity(new BlockPos(4,1,1));terminal.owner(owner.getUUID());String hall=manager.enroll(owner,terminal,true).id;
        ItemStack card=new ItemStack(SeatsModule.BINDER.get());owner.setItemInHand(InteractionHand.MAIN_HAND,card);use(owner,manager.getBlockPos());
        for(int x=1;x<=5;x++)for(int z=3;z<=5;z++){BlockPos p=h.absolutePos(new BlockPos(x,2,z));h.getLevel().setBlockAndUpdate(p.below(),Blocks.STONE.defaultBlockState());h.getLevel().setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());}
        return new Fixture(owner,manager,card,hall,SeatStorage.get(h.getLevel(),manager.identity).registry());
    }
    private static CinemaSeatTile chair(GameTestHelper h,int x,int z){
        BlockPos pos=h.absolutePos(new BlockPos(x,2,z));h.getLevel().setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());h.getLevel().setBlockAndUpdate(pos,SeatsModule.CHAIR.get().defaultBlockState().setValue(CinemaSeatBlock.FACING,Direction.NORTH));
        return CinemaSeatBlock.findSeat(h.getLevel(),pos).orElseThrow();
    }
    private static CinemaSeatTile replace(GameTestHelper h,CinemaSeatTile old){BlockPos pos=old.getBlockPos();h.getLevel().setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());h.getLevel().setBlockAndUpdate(pos,SeatsModule.CHAIR.get().defaultBlockState().setValue(CinemaSeatBlock.FACING,Direction.NORTH));return CinemaSeatBlock.findSeat(h.getLevel(),pos).orElseThrow();}
    private static RowSelectionState state(Fixture f){return SeatBindingItem.rowSelection(f.card(),f.owner(),f.manager());}
    private static RowBatchController.Args settings(Fixture f,int row,int start,int direction,int end,GuidePreset.RowScheme scheme){
        RowBatchController.Args a=new RowBatchController.Args();a.card=SeatBindingItem.card(f.card());a.revision=f.registry().revision();a.hall=f.hall();a.zone="Lower";a.row=row;a.number=start;a.direction=direction;a.end=end;a.rowScheme=scheme.name();return a;
    }
    private static RowBatchController.Args settings(Fixture f){return settings(f,1,1,1,0,GuidePreset.RowScheme.NUMERIC);}
    private static SeatNetwork.Request packet(Fixture f,String action,Object args){return new SeatNetwork.Request(f.manager().getBlockPos(),f.manager().identity.toString(),UUID.randomUUID().toString(),action,SeatRegistry.JSON.toJson(args));}
    /** Direct controller handler avoids unrelated two-tick network throttling in synchronous assertions. */
    private static void apply(Fixture f,String action,RowBatchController.Args args){RowBatchController.apply(f.owner(),f.manager(),packet(f,action,args));}
    private static void start(Fixture f){apply(f,"row_batch_start",settings(f));}
    private static void select(Fixture f,CinemaSeatTile first,CinemaSeatTile last){RowBatchController.click(f.owner(),f.manager(),f.card(),first.getBlockPos());RowBatchController.click(f.owner(),f.manager(),f.card(),last.getBlockPos());}
    /** Read-only test inspection obtains the nonce actually generated by the server; never invent a valid token. */
    private static PendingView pending(Fixture f){
        try{
            Field field=RowBatchController.class.getDeclaredField("PENDING");field.setAccessible(true);
            Object value=((Map<?,?>)field.get(null)).get(f.owner().getUUID());if(value==null)return null;
            Field token=value.getClass().getDeclaredField("token"),preview=value.getClass().getDeclaredField("preview");token.setAccessible(true);preview.setAccessible(true);
            return new PendingView((String)token.get(value),(RowBatch.Preview)preview.get(value));
        }catch(ReflectiveOperationException failure){throw new AssertionError("Cannot inspect actual server preview",failure);}
    }
    private static PendingView requiredPending(Fixture f){PendingView pending=pending(f);if(pending==null)throw new AssertionError("Expected actual server preview");return pending;}
    private static void confirm(Fixture f,PendingView pending){RowBatchController.Args a=settings(f);a.token=pending.token();apply(f,"row_batch_confirm",a);}
    private static void reject(GameTestHelper h,Runnable operation,String message){boolean rejected=false;try{operation.run();}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,message);}
    private static void unchanged(GameTestHelper h,Fixture f,String before,long revision,String reason){h.assertTrue(f.registry().revision()==revision&&f.registry().encode().equals(before),reason+": exact registry bytes and revision preserved");}
    private static void noClaims(GameTestHelper h,CinemaSeatTile... chairs){for(CinemaSeatTile chair:chairs)h.assertTrue(chair.boundController().isEmpty(),"failed or preview-only work leaves every new physical chair unclaimed");}
    private static List<CompoundTag> saved(GameTestHelper h,CinemaSeatTile... chairs){return Arrays.stream(chairs).map(c->c.saveWithFullMetadata(h.getLevel().registryAccess())).toList();}
    private static SeatRegistry.Seat index(Fixture f,CinemaSeatTile chair,int row,int number){
        SeatRegistry.Seat seat=f.registry().put(f.registry().revision(),"",f.hall(),"Lower",row,number,chair.getBlockPos().asLong(),SeatRegistry.Status.DISABLED,SeatRegistry.PHYSICAL_BLOCK,chair.seatUid().toString(),GuidePreset.RowScheme.NUMERIC);chair.bindController(f.manager().identity);return seat;
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void row_batch_real_card_endpoints_preview_confirm_and_next_row(GameTestHelper h){
        Fixture f=fixture(h);CinemaSeatTile a=chair(h,1,4),existing=chair(h,3,4),b=chair(h,4,4),c=chair(h,5,4);SeatRegistry.Seat old=index(f,existing,26,77);
        RowBatchController.Args config=settings(f,26,5,1,7,GuidePreset.RowScheme.LETTERS);
        SeatNetwork.apply(f.owner(),packet(f,"row_batch_start",config));h.assertTrue(state(f)!=null&&state(f).firstPosition()==null,"real server protocol configures an explicit row without inventing positions");
        String before=f.registry().encode();long revision=f.registry().revision();List<CompoundTag> blocks=saved(h,a,b,c);
        use(f.owner(),a.getBlockPos());h.assertTrue(state(f).firstPosition()==a.getBlockPos().asLong()&&state(f).firstUid().equals(a.seatUid().toString()),"first real card click captures canonical position and physical UID");
        use(f.owner(),c.getBlockPos());PendingView p=requiredPending(f);
        h.assertTrue(p.preview().canCommit()&&p.preview().count()==3&&p.preview().gaps()==1&&p.preview().skipped()==1,"explicit span preview reports three new chairs, one aisle and one indexed skip");
        h.assertTrue(p.preview().assignments().stream().map(RowBatch.Assignment::number).toList().equals(List.of(5,6,7)),"aisles and existing indices consume no numbers");
        unchanged(h,f,before,revision,"preview is read-only");h.assertTrue(saved(h,a,b,c).equals(blocks),"preview leaves physical block data byte-equivalent");noClaims(h,a,b,c);
        confirm(f,p);h.assertTrue(f.registry().revision()==revision+1&&f.registry().seats().size()==4,"one confirmation commits the whole row with exactly one registry revision");
        h.assertTrue(f.registry().find(old.id()).equals(old),"existing indexed chair and status are preserved");
        for(CinemaSeatTile chair:List.of(a,b,c)){
            SeatRegistry.Seat seat=f.registry().at(chair.getBlockPos().asLong()).orElseThrow();
            h.assertTrue(seat.seatUid().equals(chair.seatUid().toString())&&!seat.id().equals(seat.seatUid())&&seat.row()==26&&seat.rowLabel().equals("Z"),"physical UID, numeric row identity and plain letter display are distinct");
            h.assertTrue(chair.boundController().orElseThrow().equals(f.manager().identity)&&SeatBindings.status(h.getLevel(),seat).equals("present"),"committed physical chair is claimed and identity-verified");
        }
        h.assertTrue(!state(f).active()&&state(f).firstPosition()==null,"successful row clears live endpoints");
        String committed=f.registry().encode();confirm(f,p);unchanged(h,f,committed,revision+1,"replayed nonce cannot create duplicate entries");
        apply(f,"row_batch_next",settings(f));h.assertTrue(state(f).active()&&state(f).settings().row()==27&&state(f).settings().rowLabel().equals("AA")&&state(f).settings().start()==5&&state(f).settings().end()==7,"explicit next row preserves numbering and advances Z to AA");
        apply(f,"row_batch_exit",settings(f));h.assertTrue(state(f)==null&&pending(f)==null,"exit clears row mode and pending confirmation");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void row_batch_endpoint_owner_card_dimension_reach_and_geometry(GameTestHelper h){
        Fixture f=fixture(h);CinemaSeatTile a=chair(h,1,4),b=chair(h,5,4);start(f);RowSelectionState initial=state(f);String before=f.registry().encode();
        BlockPos wrong=h.absolutePos(new BlockPos(3,2,3));h.getLevel().setBlockAndUpdate(wrong,Blocks.OAK_STAIRS.defaultBlockState());
        reject(h,()->RowBatchController.click(f.owner(),f.manager(),f.card(),wrong),"generic stairs cannot be an actual-row endpoint");
        f.owner().setPos(b.getBlockPos().getCenter().add(9,0,0));reject(h,()->RowBatchController.click(f.owner(),f.manager(),f.card(),b.getBlockPos()),"endpoint click outside eight blocks is rejected");f.owner().setPos(h.absolutePos(new BlockPos(3,2,2)).getCenter());
        reject(h,()->RowBatchController.click(f.owner(),f.manager(),f.card().copy(),a.getBlockPos()),"detached card copy is not the exact held stack");
        ServerPlayer stranger=actor(h,"RowBatchGuest");ItemStack copied=f.card().copy();stranger.setItemInHand(InteractionHand.MAIN_HAND,copied);
        reject(h,()->RowBatchController.click(stranger,f.manager(),copied,a.getBlockPos()),"copied metadata cannot grant manager ownership");
        UUID owner=f.manager().owner;f.manager().owner=stranger.getUUID();
        reject(h,()->RowBatchController.click(f.owner(),f.manager(),f.card(),a.getBlockPos()),"revoked owner cannot continue selection");
        reject(h,()->RowBatchController.click(stranger,f.manager(),copied,a.getBlockPos()),"new owner cannot inherit another actor's row state");f.manager().owner=owner;
        CompoundTag metadata=f.card().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag(),wrongDimension=metadata.copy();wrongDimension.putString("dimension","minecraft:the_nether");f.card().set(DataComponents.CUSTOM_DATA,CustomData.of(wrongDimension));
        reject(h,()->RowBatchController.click(f.owner(),f.manager(),f.card(),a.getBlockPos()),"card from another dimension cannot select endpoints");f.card().set(DataComponents.CUSTOM_DATA,CustomData.of(metadata));
        RowSelectionState foreign=new RowSelectionState(initial.actor(),initial.controller(),"minecraft:the_nether",initial.manager(),initial.card(),initial.settings(),true,null,"","");SeatBindingItem.rowSelection(f.card(),foreign);
        reject(h,()->RowBatchController.click(f.owner(),f.manager(),f.card(),a.getBlockPos()),"row state's dimension is checked independently of card metadata");SeatBindingItem.rowSelection(f.card(),initial);
        f.owner().setGameMode(GameType.SPECTATOR);reject(h,()->RowBatchController.click(f.owner(),f.manager(),f.card(),a.getBlockPos()),"spectators cannot configure or select rows");f.owner().setGameMode(GameType.SURVIVAL);
        BlockPos unloaded=new BlockPos(20000000,64,20000000);h.assertFalse(h.getLevel().hasChunkAt(unloaded),"remote fixture starts unloaded");reject(h,()->RowBatchController.click(f.owner(),f.manager(),f.card(),unloaded),"unloaded endpoint cannot cause chunk loading");h.assertFalse(h.getLevel().hasChunkAt(unloaded),"rejected row endpoint remains unloaded");
        h.assertTrue(state(f).equals(initial),"all endpoint authorization failures preserve the exact card state");unchanged(h,f,before,0,"endpoint failures");
        RowBatchController.click(f.owner(),f.manager(),f.card(),a.getBlockPos());RowSelectionState first=state(f);
        reject(h,()->RowBatchController.candidates(f.owner(),f.manager(),first,a.getBlockPos().offset(1,0,1).asLong(),b.seatUid().toString()),"diagonal endpoints are rejected");
        reject(h,()->RowBatchController.candidates(f.owner(),f.manager(),first,b.getBlockPos().above().asLong(),b.seatUid().toString()),"a row cannot span different floor heights");
        reject(h,()->RowBatchController.candidates(f.owner(),f.manager(),first,a.getBlockPos().north(2).asLong(),b.seatUid().toString()),"row direction must be perpendicular to chair facing");
        reject(h,()->RowBatchController.candidates(f.owner(),f.manager(),first,a.getBlockPos().east(RowBatchController.MAX_SPAN).asLong(),b.seatUid().toString()),"129-cell span exceeds the exact 128-cell server limit");
        UUID other=UUID.randomUUID();b.bindController(other);reject(h,()->RowBatchController.click(f.owner(),f.manager(),f.card(),b.getBlockPos()),"another controller's endpoint cannot be selected");b.unbindController(other);
        h.assertTrue(state(f).equals(first),"geometry and claim failures preserve the chosen first endpoint");unchanged(h,f,before,0,"span failures");RowBatchController.clear(f.owner());h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void row_batch_number_conflict_wrong_block_orientation_and_uid_are_atomic(GameTestHelper h){
        Fixture f=fixture(h);CinemaSeatTile a=chair(h,1,4),middle=chair(h,3,4),last=chair(h,5,4);
        BlockPos outside=h.absolutePos(new BlockPos(2,2,3));h.getLevel().setBlockAndUpdate(outside,Blocks.OAK_STAIRS.defaultBlockState());
        SeatRegistry.Seat conflict=f.registry().put(0,"",f.hall(),"lower",1,2,outside.asLong(),SeatRegistry.Status.MAINTENANCE,"minecraft:oak_stairs");
        start(f);select(f,a,last);PendingView p=requiredPending(f);h.assertTrue(p.preview().errors().stream().anyMatch(i->i.code().equals("mycinema.seats.row_number_conflict")),"case-insensitive logical collision blocks a complete preview");
        String before=f.registry().encode();long revision=f.registry().revision();List<CompoundTag> chairs=saved(h,a,middle,last);confirm(f,p);
        unchanged(h,f,before,revision,"late row number conflict");h.assertTrue(saved(h,a,middle,last).equals(chairs),"all staged physical claims roll back after a blocked commit");noClaims(h,a,middle,last);
        f.registry().remove(revision,conflict.id());start(f);select(f,a,last);PendingView replaced=requiredPending(f);CinemaSeatTile fresh=replace(h,middle);
        h.assertFalse(fresh.seatUid().equals(middle.seatUid()),"same-position replacement gets a different physical UID");
        before=f.registry().encode();revision=f.registry().revision();chairs=saved(h,a,fresh,last);confirm(f,replaced);
        unchanged(h,f,before,revision,"interior replacement after preview");h.assertTrue(saved(h,a,fresh,last).equals(chairs),"replaced-UID failure leaves every physical claim unchanged");noClaims(h,a,fresh,last);
        start(f);select(f,a,last);PendingView staleEndpoint=requiredPending(f);CinemaSeatTile endpoint=replace(h,last);before=f.registry().encode();confirm(f,staleEndpoint);unchanged(h,f,before,revision,"endpoint replacement after preview");noClaims(h,a,fresh,endpoint);
        start(f);h.getLevel().setBlockAndUpdate(fresh.getBlockPos(),fresh.getBlockState().setValue(CinemaSeatBlock.FACING,Direction.EAST));select(f,a,endpoint);PendingView mixed=requiredPending(f);
        h.assertTrue(mixed.preview().errors().stream().anyMatch(i->i.code().equals("mycinema.seats.row_mixed_facing")),"mixed orientation is a visible blocking error");before=f.registry().encode();confirm(f,mixed);unchanged(h,f,before,revision,"mixed orientation");noClaims(h,a,fresh,endpoint);
        h.getLevel().setBlockAndUpdate(fresh.getBlockPos(),Blocks.STONE.defaultBlockState());start(f);select(f,a,endpoint);PendingView obstruction=requiredPending(f);
        h.assertTrue(obstruction.preview().errors().stream().anyMatch(i->i.code().equals("mycinema.seats.row_wrong_block")),"obstruction is not silently treated as an aisle");before=f.registry().encode();confirm(f,obstruction);unchanged(h,f,before,revision,"wrong block");noClaims(h,a,endpoint);h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void row_batch_pending_revision_claim_nonce_reload_restart_and_exit(GameTestHelper h){
        Fixture f=fixture(h);CinemaSeatTile a=chair(h,1,4),middle=chair(h,3,4),last=chair(h,5,4);start(f);select(f,a,last);PendingView p=requiredPending(f);
        RowBatchController.Args forged=settings(f);forged.token=UUID.randomUUID().toString();String before=f.registry().encode();apply(f,"row_batch_confirm",forged);unchanged(h,f,before,0,"invented nonce");noClaims(h,a,middle,last);
        f.registry().put(0,"",f.hall(),"Other",1,1,h.absolutePos(new BlockPos(2,2,3)).asLong(),SeatRegistry.Status.AVAILABLE);before=f.registry().encode();confirm(f,p);unchanged(h,f,before,1,"registry changed since preview");noClaims(h,a,middle,last);
        start(f);select(f,a,last);PendingView claimed=requiredPending(f);UUID foreign=UUID.randomUUID();middle.bindController(foreign);before=f.registry().encode();confirm(f,claimed);unchanged(h,f,before,1,"claim changed after preview");
        h.assertTrue(middle.boundController().orElseThrow().equals(foreign),"failed confirmation never clears another controller's claim");noClaims(h,a,last);middle.unbindController(foreign);
        start(f);select(f,a,last);PendingView beforeReload=requiredPending(f);RowSelectionState selected=state(f);
        ItemStack restored=ItemStack.parse(h.getLevel().registryAccess(),f.card().save(h.getLevel().registryAccess())).orElseThrow();f.owner().setItemInHand(InteractionHand.MAIN_HAND,restored);
        h.assertTrue(SeatBindingItem.rowSelection(restored,f.owner(),f.manager()).equals(selected),"item codec retains exact owner/card/dimension/endpoint UID scope");
        RowBatchController.clear(f.owner());RowBatchController.Args stale=settings(f);stale.card=SeatBindingItem.card(restored);stale.token=beforeReload.token();before=f.registry().encode();RowBatchController.apply(f.owner(),f.manager(),packet(f,"row_batch_confirm",stale));unchanged(h,f,before,1,"expired server preview is not restored from an item");
        f.owner().setItemInHand(InteractionHand.MAIN_HAND,f.card());start(f);select(f,a,last);PendingView beforeRestart=requiredPending(f);apply(f,"row_batch_restart",settings(f));
        h.assertTrue(state(f).active()&&state(f).firstPosition()==null&&pending(f)==null,"restart invalidates nonce and clears endpoints");before=f.registry().encode();confirm(f,beforeRestart);unchanged(h,f,before,1,"confirmation after restart");
        start(f);select(f,a,last);f.manager().identity=UUID.randomUUID();f.owner().setShiftKeyDown(true);((SeatBindingItem)f.card().getItem()).use(h.getLevel(),f.owner(),InteractionHand.MAIN_HAND);f.owner().setShiftKeyDown(false);
        h.assertFalse(f.card().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().contains(SeatBindingItem.ROW_SELECTION),"shift-air exit clears saved row state even after manager replacement");h.assertTrue(pending(f)==null,"shift-air exit also discards stale server preview");noClaims(h,a,middle,last);h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void row_batch_manual_continuous_physical_uid_claim_and_legacy_compatibility(GameTestHelper h){
        Fixture f=fixture(h);CinemaSeatTile manual=chair(h,1,4),continuous=chair(h,3,4),other=chair(h,5,4);
        SeatNetwork.Args add=new SeatNetwork.Args();add.revision=0L;add.hall=f.hall();add.zone="Lower";add.row=1;add.number=1;add.target=true;add.position=manual.getBlockPos().asLong();SeatNetwork.apply(f.owner(),packet(f,"save",add));
        SeatRegistry.Seat first=f.registry().at(manual.getBlockPos().asLong()).orElseThrow();h.assertTrue(first.seatUid().equals(manual.seatUid().toString())&&manual.boundController().orElseThrow().equals(f.manager().identity),"legacy manual server route captures UID and claim for new actual chairs");
        SeatNetwork.Args mode=new SeatNetwork.Args();mode.card=SeatBindingItem.card(f.card());mode.revision=1L;mode.hall=f.hall();mode.zone="Lower";mode.row=2;mode.number=10;mode.direction=1;
        SeatNetwork.configureContinuous(f.owner(),f.manager(),mode,"continuous_start");SeatNetwork.bindContinuous(f.owner(),f.manager(),f.card(),continuous.getBlockPos());
        SeatRegistry.Seat second=f.registry().at(continuous.getBlockPos().asLong()).orElseThrow();h.assertTrue(second.seatUid().equals(continuous.seatUid().toString())&&continuous.boundController().orElseThrow().equals(f.manager().identity),"continuous route also stores immutable physical UID");
        BlockPos legacy=h.absolutePos(new BlockPos(2,2,3));h.getLevel().setBlockAndUpdate(legacy,Blocks.OAK_STAIRS.defaultBlockState());SeatNetwork.bindContinuous(f.owner(),f.manager(),f.card(),legacy);
        SeatRegistry.Seat old=f.registry().at(legacy.asLong()).orElseThrow();h.assertTrue(old.seatUid().isEmpty()&&SeatBindings.status(h.getLevel(),old).equals("unverified"),"generic legacy single-click binding remains unverified");
        ContinuousSeatState cursor=SeatBindingItem.continuous(f.card(),f.owner(),f.manager());CinemaSeatTile replacement=replace(h,continuous);String before=f.registry().encode();
        reject(h,()->SeatNetwork.bindContinuous(f.owner(),f.manager(),f.card(),replacement.getBlockPos()),"continuous skip cannot silently accept a replaced indexed physical UID");unchanged(h,f,before,3,"replaced continuous target");
        h.assertTrue(SeatBindingItem.continuous(f.card(),f.owner(),f.manager()).equals(cursor)&&SeatBindings.status(h.getLevel(),second).equals("replaced"),"failed replacement leaves cursor fixed and exposes identity diagnostic");
        UUID foreign=UUID.randomUUID();other.bindController(foreign);reject(h,()->SeatNetwork.bindContinuous(f.owner(),f.manager(),f.card(),other.getBlockPos()),"continuous mode rejects another controller's physical chair");other.unbindController(foreign);
        // Suppress neighbor shape updates deliberately: a present physical block with invalid support
        // must fail resolution, rather than be reclassified as a generic block-only binding.
        h.getLevel().setBlock(other.getBlockPos().below(),Blocks.AIR.defaultBlockState(),2);
        reject(h,()->SeatNetwork.bindContinuous(f.owner(),f.manager(),f.card(),other.getBlockPos()),"physical block without valid support cannot downgrade to legacy identity");unchanged(h,f,before,3,"invalid actual chair");
        h.runAfterDelay(3,()->{
            SeatNetwork.Args remove=new SeatNetwork.Args();remove.revision=3L;remove.id=first.id();remove.confirmed=true;SeatNetwork.apply(f.owner(),packet(f,"delete",remove));
            h.assertTrue(f.registry().revision()==4&&manual.boundController().isEmpty()&&f.registry().at(manual.getBlockPos().asLong()).isEmpty(),"explicit deletion releases only the matching physical UID/controller claim");
        });
        h.runAfterDelay(6,()->{
            String current=f.registry().encode();SeatNetwork.Args broken=new SeatNetwork.Args();broken.revision=4L;broken.hall=f.hall();broken.zone="Lower";broken.row=3;broken.number=1;broken.target=true;broken.position=other.getBlockPos().asLong();SeatNetwork.apply(f.owner(),packet(f,"save",broken));unchanged(h,f,current,4,"manual route rejects invalid physical support");
        });
        h.runAfterDelay(9,()->{
            h.getLevel().setBlockAndUpdate(legacy,Blocks.AIR.defaultBlockState());h.getLevel().setBlockAndUpdate(legacy,SeatsModule.CHAIR.get().defaultBlockState());CinemaSeatTile actual=CinemaSeatBlock.findSeat(h.getLevel(),legacy).orElseThrow();
            SeatNetwork.Args edit=new SeatNetwork.Args();edit.revision=4L;edit.id=old.id();edit.hall=old.hall();edit.zone=old.zone();edit.row=old.row();edit.number=old.number();edit.status="MAINTENANCE";SeatNetwork.apply(f.owner(),packet(f,"save",edit));
            h.assertTrue(f.registry().revision()==5&&f.registry().find(old.id()).seatUid().isEmpty()&&actual.boundController().isEmpty(),"editing an old block-only record never adopts its replacement chair's UID or claim");h.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void row_batch_malformed_wire_and_invalid_saved_registry_preserve_state(GameTestHelper h){
        Fixture f=fixture(h);CinemaSeatTile first=chair(h,1,4),last=chair(h,5,4);start(f);RowSelectionState initial=state(f);
        CompoundTag original=f.card().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();String before=f.registry().encode();
        String base=SeatRegistry.JSON.toJson(settings(f,2,2,1,0,GuidePreset.RowScheme.NUMERIC));List<String> invalid=new ArrayList<>();
        for(String key:List.of("revision","row","number","direction","end")){
            JsonObject object=JsonParser.parseString(base).getAsJsonObject();object.addProperty(key,"2");invalid.add(object.toString());
            object=JsonParser.parseString(base).getAsJsonObject();object.addProperty(key,2.5);invalid.add(object.toString());
        }
        for(String key:List.of("card","hall","zone","rowScheme","token")){
            JsonObject object=JsonParser.parseString(base).getAsJsonObject();object.addProperty(key,123);invalid.add(object.toString());
        }
        invalid.add("{broken}");invalid.add("null");invalid.add(" ".repeat(8193));
        for(String raw:invalid){
            RowBatchController.apply(f.owner(),f.manager(),new SeatNetwork.Request(f.manager().getBlockPos(),f.manager().identity.toString(),UUID.randomUUID().toString(),"row_batch_start",raw));
            h.assertTrue(state(f).equals(initial)&&f.card().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().equals(original),"malformed packet cannot coerce, replace or erase a valid row configuration");unchanged(h,f,before,0,"malformed row packet");
        }
        SeatNetwork.apply(f.owner(),new SeatNetwork.Request(f.manager().getBlockPos(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"row_batch_start",base));
        h.assertTrue(state(f).equals(initial),"outer protocol rejects a replaced controller identity before dispatch");
        select(f,first,last);PendingView pending=requiredPending(f);RowSelectionState chosen=state(f);
        CompoundTag broken=new CompoundTag();broken.putString("registry","{invalid-row-inventory}");SeatStorage locked=SeatStorage.load(broken,h.getLevel().registryAccess());
        h.getLevel().getDataStorage().set("mycinema_seats_"+f.manager().identity,locked);confirm(f,pending);
        h.assertTrue(locked.save(new CompoundTag(),h.getLevel().registryAccess()).getString("registry").equals("{invalid-row-inventory}"),"read-only damaged inventory backup is preserved exactly");
        h.assertTrue(state(f).equals(chosen)&&locked.registry().seats().isEmpty(),"read-only storage cannot complete or reset a pending row");noClaims(h,first,last);
        apply(f,"row_batch_exit",settings(f));h.assertTrue(state(f)==null&&pending(f)==null,"explicit local exit still works with a damaged read-only registry");
        h.assertTrue(locked.save(new CompoundTag(),h.getLevel().registryAccess()).getString("registry").equals("{invalid-row-inventory}"),"exiting preserves the damaged backup");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void row_batch_delete_unloaded_invalid_support_and_replacement_claim_lifecycle(GameTestHelper h){
        Fixture f=fixture(h);BlockPos remote=new BlockPos(20000000,64,20000000);h.assertFalse(h.getLevel().hasChunkAt(remote),"remote registered position begins unloaded");
        SeatRegistry.Seat remoteSeat=f.registry().put(0,"",f.hall(),"Remote",3,9,remote.asLong(),SeatRegistry.Status.AVAILABLE,SeatRegistry.PHYSICAL_BLOCK,UUID.randomUUID().toString(),GuidePreset.RowScheme.NUMERIC);
        CinemaSeatTile unsupported=chair(h,1,4),original=chair(h,5,4);SeatRegistry.Seat a=index(f,unsupported,1,1),b=index(f,original,1,2);
        // Keep the physical tile installed while deliberately invalidating its support. Client-only
        // updates still propagate neighbor shapes; without UPDATE_KNOWN_SHAPE the chair is removed.
        h.getLevel().setBlock(unsupported.getBlockPos().below(),Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);CinemaSeatTile replacement=replace(h,original);UUID foreign=UUID.randomUUID();replacement.bindController(foreign);
        SeatNetwork.Args remoteDelete=new SeatNetwork.Args();remoteDelete.revision=3L;remoteDelete.id=remoteSeat.id();remoteDelete.confirmed=true;String before=f.registry().encode();SeatNetwork.apply(f.owner(),packet(f,"delete",remoteDelete));
        unchanged(h,f,before,3,"unloaded physical delete cannot orphan an inaccessible claim");h.assertFalse(h.getLevel().hasChunkAt(remote),"delete never force-loads physical chair chunks");
        h.runAfterDelay(3,()->{
            h.assertTrue(h.getLevel().getBlockEntity(unsupported.getBlockPos())==unsupported&&!unsupported.isRemoved()&&unsupported.seatUid().toString().equals(a.seatUid()),"unsupported fixture retains the live matching physical tile until deletion");
            h.assertFalse(h.getLevel().getBlockState(unsupported.getBlockPos()).canSurvive(h.getLevel(),unsupported.getBlockPos()),"physical tile support is invalid before deletion");
            h.assertTrue(unsupported.boundController().orElseThrow().equals(f.manager().identity),"unsupported physical tile still holds the matching controller claim before deletion");
            SeatNetwork.Args remove=new SeatNetwork.Args();remove.revision=3L;remove.id=a.id();remove.confirmed=true;SeatNetwork.apply(f.owner(),packet(f,"delete",remove));
            h.assertTrue(f.registry().revision()==4&&f.registry().at(a.position()).isEmpty()&&unsupported.boundController().isEmpty(),"loaded matching physical UID can release its claim even after support became invalid");
        });
        h.runAfterDelay(6,()->{
            SeatNetwork.Args remove=new SeatNetwork.Args();remove.revision=4L;remove.id=b.id();remove.confirmed=true;SeatNetwork.apply(f.owner(),packet(f,"delete",remove));
            h.assertTrue(f.registry().revision()==5&&f.registry().at(b.position()).isEmpty()&&replacement.boundController().orElseThrow().equals(foreign),"deleting an obsolete UID never unclaims a replacement chair or another controller");
            h.assertTrue(f.registry().seats().equals(List.of(remoteSeat))&&!h.getLevel().hasChunkAt(remote),"unloaded record remains recoverable and no unrelated entry is removed");h.succeed();
        });
    }
}
