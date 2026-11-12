package local.cinemamanager.seats;

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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.*;
import java.util.UUID;

/** Exercise real held-card components, loaded blocks, ownership and the server request path. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class ContinuousSeatGameTests {
    private record Fixture(ServerPlayer owner,ManagerTile manager,ItemStack card,String hall,SeatRegistry registry){}
    private static ServerPlayer actor(GameTestHelper h,String name){ServerPlayer actor=new FakePlayer(h.getLevel(),new GameProfile(UUID.randomUUID(),name));actor.setGameMode(GameType.SURVIVAL);actor.setPos(h.absolutePos(new BlockPos(3,2,2)).getCenter());return actor;}
    private static UseOnContext click(ServerPlayer actor,BlockPos pos){return new UseOnContext(actor,InteractionHand.MAIN_HAND,new BlockHitResult(pos.getCenter(),Direction.UP,pos,false));}
    private static void use(ServerPlayer actor,BlockPos pos){((SeatBindingItem)actor.getMainHandItem().getItem()).onItemUseFirst(actor.getMainHandItem(),click(actor,pos));}
    private static void reject(GameTestHelper h,Runnable work,String message){boolean rejected=false;try{work.run();}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,message);}
    private static Fixture fixture(GameTestHelper h){
        ServerPlayer owner=actor(h,"ContSeatOwner");h.setBlock(new BlockPos(1,1,1),CinemaManager.MANAGER.get());ManagerTile manager=h.getBlockEntity(new BlockPos(1,1,1));manager.initializePlacement(owner.getUUID());
        h.setBlock(new BlockPos(4,1,1),CinemaDisplays.TMS_TERMINAL.get());CinemaTerminalTile terminal=h.getBlockEntity(new BlockPos(4,1,1));terminal.owner(owner.getUUID());String hall=manager.enroll(owner,terminal,true).id;
        ItemStack card=new ItemStack(SeatsModule.BINDER.get());owner.setItemInHand(InteractionHand.MAIN_HAND,card);use(owner,manager.getBlockPos());
        return new Fixture(owner,manager,card,hall,SeatStorage.get(h.getLevel(),manager.identity).registry());
    }
    private static SeatNetwork.Args settings(Fixture f,int row,int start,int direction,int end){SeatNetwork.Args args=new SeatNetwork.Args();args.card=SeatBindingItem.card(f.card());args.revision=f.registry().revision();args.hall=f.hall();args.zone="Lower";args.row=row;args.number=start;args.direction=direction;args.end=end;return args;}
    private static void request(Fixture f,String action,SeatNetwork.Args args){SeatNetwork.apply(f.owner(),new SeatNetwork.Request(f.manager().getBlockPos(),f.manager().identity.toString(),UUID.randomUUID().toString(),action,SeatRegistry.JSON.toJson(args)));}
    private static ContinuousSeatState state(Fixture f){return SeatBindingItem.continuous(f.card(),f.owner(),f.manager());}
    private static BlockPos seat(GameTestHelper h,int x,int z){BlockPos p=h.absolutePos(new BlockPos(x,1,z));h.getLevel().setBlockAndUpdate(p,Blocks.OAK_STAIRS.defaultBlockState());return p;}

    @GameTest(template="empty",timeoutTicks=50)
    public static void continuous_click_order_skip_end_next_row_and_exit(GameTestHelper h){
        Fixture f=fixture(h);BlockPos a=seat(h,2,4),b=seat(h,4,4),c=seat(h,3,4),untouched=seat(h,5,4);
        request(f,"continuous_start",settings(f,2,8,1,9));h.assertTrue(state(f)!=null&&state(f).next()==8,"server request starts only the explicitly configured row");
        use(f.owner(),a);h.assertTrue(f.registry().at(a.asLong()).orElseThrow().number()==8&&state(f).next()==9,"first real card click saves number 8");
        use(f.owner(),a);h.assertTrue(f.registry().revision()==1&&state(f).next()==9,"already indexed block skips without consuming number");
        use(f.owner(),b);h.assertTrue(f.registry().at(b.asLong()).orElseThrow().number()==9&&!state(f).active(),"inclusive end stops the mode");
        use(f.owner(),c);h.assertTrue(f.registry().at(c.asLong()).isEmpty()&&f.registry().revision()==2,"stopped mode cannot silently continue");
        h.assertTrue(f.registry().at(untouched.asLong()).isEmpty()&&h.getLevel().getBlockState(untouched).is(Blocks.OAK_STAIRS),"no scanning or seat placement occurs");
        h.runAfterDelay(3,()->{
            request(f,"continuous_next",settings(f,2,8,1,9));h.assertTrue(state(f).row()==3&&state(f).next()==8&&state(f).active(),"explicit next-row action uses the saved original start");
            use(f.owner(),c);h.assertTrue(f.registry().at(c.asLong()).orElseThrow().row()==3&&state(f).next()==9,"chosen third block belongs to explicitly selected next row");
        });
        h.runAfterDelay(6,()->{SeatNetwork.Args stale=settings(f,3,8,1,9);stale.revision=0L;request(f,"continuous_stop",stale);h.assertTrue(!SeatBindingItem.configured(f.card()),"explicit exit always clears mode even with an old editor revision");use(f.owner(),untouched);h.assertTrue(f.registry().seats().size()==3,"ordinary edit mode does not auto-register after exit");h.succeed();});
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void continuous_failures_reload_ownership_and_relink_are_atomic(GameTestHelper h){
        Fixture f=fixture(h);BlockPos a=seat(h,2,4),b=seat(h,3,4),c=seat(h,4,4);
        f.registry().put(0,"",f.hall(),"Lower",1,5,a.asLong(),SeatRegistry.Status.AVAILABLE,"minecraft:oak_stairs");
        SeatNetwork.configureContinuous(f.owner(),f.manager(),settings(f,1,5,1,0),"continuous_start");
        reject(h,()->SeatNetwork.bindContinuous(f.owner(),f.manager(),f.card(),b),"duplicate logical number refuses the unregistered position");
        h.assertTrue(state(f).next()==5&&f.registry().revision()==1,"duplicate number preserves cursor and registry");
        SeatNetwork.configureContinuous(f.owner(),f.manager(),settings(f,1,6,1,0),"continuous_start");
        ContinuousSeatState before=state(f);
        reject(h,()->SeatNetwork.bindContinuous(f.owner(),f.manager(),f.card(),new BlockPos(20000000,64,20000000)),"unloaded target cannot load a chunk");
        reject(h,()->SeatNetwork.bindContinuous(f.owner(),f.manager(),f.card(),h.absolutePos(new BlockPos(3,3,4))),"air cannot be registered");
        f.owner().setPos(b.getCenter().add(9,0,0));reject(h,()->SeatNetwork.bindContinuous(f.owner(),f.manager(),f.card(),b),"out-of-reach target rejected with a remotely bound manager");f.owner().setPos(b.getCenter().add(0,1,-2));
        f.owner().setGameMode(GameType.SPECTATOR);reject(h,()->SeatNetwork.bindContinuous(f.owner(),f.manager(),f.card(),b),"spectators cannot use continuous registration");f.owner().setGameMode(GameType.SURVIVAL);
        ServerPlayer stranger=actor(h,"ContSeatGuest");ItemStack copied=f.card().copy();stranger.setItemInHand(InteractionHand.MAIN_HAND,copied);
        reject(h,()->SeatNetwork.bindContinuous(stranger,f.manager(),copied,b),"copied card never grants manager authority");
        UUID owner=f.manager().owner;f.manager().owner=stranger.getUUID();reject(h,()->SeatNetwork.bindContinuous(f.owner(),f.manager(),f.card(),b),"revoked owner cannot continue");
        reject(h,()->SeatNetwork.bindContinuous(stranger,f.manager(),copied,b),"new owner cannot inherit another actor's running cursor");f.manager().owner=owner;
        h.assertTrue(state(f).equals(before)&&f.registry().revision()==1,"all permission, actor, reach and target failures preserve the next number");
        // Real ItemStack codec round trip, followed by a successful server-side click.
        ItemStack restored=ItemStack.parse(h.getLevel().registryAccess(),f.card().save(h.getLevel().registryAccess())).orElseThrow();f.owner().setItemInHand(InteractionHand.MAIN_HAND,restored);
        h.assertTrue(SeatBindingItem.continuous(restored,f.owner(),f.manager()).equals(before),"persistent item component retains exact cursor and actor/controller binding");
        SeatNetwork.bindContinuous(f.owner(),f.manager(),restored,b);h.assertTrue(f.registry().revision()==2&&SeatBindingItem.continuous(restored,f.owner(),f.manager()).next()==7,"reloaded card resumes after exactly one success");
        f.registry().put(2,"",f.hall(),"Other",1,1,c.asLong(),SeatRegistry.Status.AVAILABLE,"minecraft:oak_stairs");
        ContinuousSeatState stale=SeatBindingItem.continuous(restored,f.owner(),f.manager());reject(h,()->SeatNetwork.bindContinuous(f.owner(),f.manager(),restored,a),"other registry edits require explicit current-revision setup");
        h.assertTrue(SeatBindingItem.continuous(restored,f.owner(),f.manager()).equals(stale),"stale revision does not consume a number");
        UUID oldIdentity=f.manager().identity;f.manager().identity=UUID.randomUUID();reject(h,()->SeatBindingItem.controller(f.owner(),restored),"reset or replacement controller invalidates old binding");f.manager().identity=oldIdentity;
        BlockPos otherPos=h.absolutePos(new BlockPos(5,1,2));h.getLevel().setBlockAndUpdate(otherPos,CinemaManager.MANAGER.get().defaultBlockState());ManagerTile other=(ManagerTile)h.getLevel().getBlockEntity(otherPos);other.initializePlacement(f.owner().getUUID());use(f.owner(),otherPos);
        h.assertTrue(SeatBindingItem.boundTo(f.owner(),other)&&!SeatBindingItem.configured(restored),"explicit relink clears numbering rather than leaking into another cinema");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=50)
    public static void continuous_descending_bounds_invalid_state_and_safe_exit(GameTestHelper h){
        Fixture f=fixture(h);BlockPos a=seat(h,2,4),b=seat(h,3,4),c=seat(h,4,4);
        SeatNetwork.configureContinuous(f.owner(),f.manager(),settings(f,1,2,-1,0),"continuous_start");use(f.owner(),a);use(f.owner(),b);
        h.assertTrue(f.registry().at(a.asLong()).orElseThrow().number()==2&&f.registry().at(b.asLong()).orElseThrow().number()==1&&!state(f).active(),"descending mode stops at one");
        SeatNetwork.configureContinuous(f.owner(),f.manager(),settings(f,999,999,1,0),"continuous_start");use(f.owner(),c);
        h.assertTrue(f.registry().at(c.asLong()).orElseThrow().number()==999&&!state(f).active(),"upper boundary records 999 exactly once");
        reject(h,()->SeatNetwork.configureContinuous(f.owner(),f.manager(),settings(f,999,999,1,0),"continuous_next"),"next row refuses row 1000");
        CompoundTag data=f.card().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();data.putString(SeatBindingItem.CONTINUOUS,"{broken}");f.card().set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        reject(h,()->SeatNetwork.bindContinuous(f.owner(),f.manager(),f.card(),a),"malformed saved mode locks clicks without overwriting the record");
        h.assertTrue(f.card().getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getString(SeatBindingItem.CONTINUOUS).equals("{broken}")&&f.registry().revision()==3,"invalid saved mode preserved until explicit exit");
        CompoundTag brokenRegistry=new CompoundTag();brokenRegistry.putString("registry","{invalid-registry}");SeatStorage locked=SeatStorage.load(brokenRegistry,h.getLevel().registryAccess());
        h.getLevel().getDataStorage().set("mycinema_seats_"+f.manager().identity,locked);
        SeatBindingItem.continuous(f.card(),ContinuousSeatState.start(f.owner().getUUID().toString(),f.manager().identity.toString(),h.getLevel().dimension().location().toString(),f.manager().getBlockPos().asLong(),f.hall(),"Lower",1,4,1,0,0));
        reject(h,()->SeatNetwork.bindContinuous(f.owner(),f.manager(),f.card(),a),"invalid saved inventory is also read-only for continuous mode");
        h.assertTrue(locked.save(new CompoundTag(),h.getLevel().registryAccess()).getString("registry").equals("{invalid-registry}"),"failed continuous click preserves the invalid inventory backup");
        f.manager().identity=UUID.randomUUID();f.owner().setShiftKeyDown(true);((SeatBindingItem)f.card().getItem()).use(h.getLevel(),f.owner(),InteractionHand.MAIN_HAND);f.owner().setShiftKeyDown(false);
        h.assertFalse(SeatBindingItem.configured(f.card()),"shift-air exit clears broken state even after controller reset");h.succeed();
    }
}
