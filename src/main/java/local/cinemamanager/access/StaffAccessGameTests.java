package local.cinemamanager.access;

import java.util.*;
import local.cinemamanager.*;
import local.cinemamanager.doors.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.neoforged.neoforge.gametest.*;

/** Real world block/state integration. Mock actors are used only for ordinary inside egress. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class StaffAccessGameTests {
    /** Offline dedicated-world fixture only; production retains its unconditional FakePlayer rejection. */
    private static final class Actor extends net.minecraft.server.level.ServerPlayer {
        Actor(net.minecraft.server.level.ServerLevel level,UUID id){
            super(level.getServer(),level,new com.mojang.authlib.GameProfile(id,"StaffAccessQA"),net.minecraft.server.level.ClientInformation.createDefault());
            // Vanilla reach also consults connection latency when rejecting a distant click.
            // Stub transport only; keep the actual ServerPlayer reach implementation intact.
            connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(),
                    new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND),this,
                    net.minecraft.server.network.CommonListenerCookie.createInitial(getGameProfile(),false)){
                @Override public void send(net.minecraft.network.protocol.Packet<?> packet){}
                @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener listener){}
            };
        }
        @Override public BlockPos adjustSpawnLocation(net.minecraft.server.level.ServerLevel level,BlockPos pos){return pos;}
        @Override public void displayClientMessage(net.minecraft.network.chat.Component text,boolean overlay){}
    }
    private record Fixture(CinemaDoorBlock door,BlockPos relative,BlockPos base,StaffReaderTile reader,ManagerTile manager,StaffAccessRegistry.Door binding,UUID player){}
    private static Fixture fixture(GameTestHelper h){
        BlockPos pos=new BlockPos(5,1,5),readerPos=new BlockPos(2,1,4),managerPos=new BlockPos(1,1,2);var door=CinemaManager.DOOR_METAL.get();UUID owner=UUID.randomUUID();
        h.setBlock(pos.below(),Blocks.STONE);BlockState state=door.defaultBlockState().setValue(CinemaDoorBlock.FACING,Direction.NORTH).setValue(CinemaDoorBlock.HINGE,DoorHingeSide.LEFT).setValue(CinemaDoorBlock.ACCESS,DoorAccess.STAFF_FRONT);
        for(DoorPart part:DoorPart.values())h.getLevel().setBlock(h.absolutePos(pos.above(part.offset())),state.setValue(CinemaDoorBlock.PART,part),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        h.setBlock(readerPos,AccessModule.READER.get());StaffReaderTile reader=h.getBlockEntity(readerPos);reader.initialize(owner);
        h.setBlock(managerPos,CinemaManager.MANAGER.get());ManagerTile manager=h.getBlockEntity(managerPos);manager.initializePlacement(owner);
        reader.controllerPos=manager.getBlockPos();reader.controllerId=manager.identity;reader.controllerOwner=manager.owner;
        var binding=new StaffAccessRegistry.Door(UUID.randomUUID(),reader.identity,reader.getBlockPos().asLong(),List.of(new StaffAccessRegistry.Leaf(h.absolutePos(pos).asLong(),"mycinema:cinema_door_metal|north|left|staff_front|staff_front|staff_front")));
        StaffAccessRegistry.get(h.getLevel()).bind(binding);reader.doorPos=h.absolutePos(pos);reader.doorId=binding.token;
        return new Fixture(door,pos,h.absolutePos(pos),reader,manager,binding,UUID.randomUUID());
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void staff_reader_egress_lease_survives_missing_controller_and_obeys_redstone(GameTestHelper h){
        var f=fixture(h);var player=h.makeMockPlayer(GameType.SURVIVAL);player.setPos(f.base().getCenter().add(0,0,-2));
        h.assertFalse(f.door().setOpen(player,h.getLevel(),f.base(),true),"outside cannot bypass with empty hand");
        BlockPos missing=new BlockPos(200000,80,200000);h.assertFalse(h.getLevel().hasChunkAt(missing),"test controller starts unloaded");f.reader().controllerPos=missing;
        h.assertTrue(f.reader().currentScope()==null,"unloaded controller denies card scope");h.assertFalse(h.getLevel().hasChunkAt(missing),"authorization never force loads");
        player.setPos(f.base().getCenter().add(0,0,2));h.assertTrue(f.door().setOpen(player,h.getLevel(),f.base(),true),"inside egress needs no controller, card or prior lease");
        h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,true);
        h.runAfterDelay(70,()->{
            h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,false);
            h.setBlock(f.relative().below(),Blocks.REDSTONE_BLOCK);h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,true);
            h.assertTrue(AccessDoors.reconciledOpen(h.getLevel(),f.base(),null,true,false),"external positive redstone overrides expired lease");
            h.setBlock(f.relative().below(),Blocks.STONE);h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,false);h.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void staff_reader_exact_authority_and_replacement_identity(GameTestHelper h){
        var f=fixture(h);var registry=StaffAccessRegistry.get(h.getLevel());var scope=f.reader().currentScope();h.assertTrue(scope!=null,"paired current blocks yield a scope");
        var card=registry.cards.issue(f.player(),f.manager().owner,scope,StaffRole.SERVICE);h.assertTrue(registry.cards.authorize(card.card(),f.player(),f.reader().currentScope(),StaffRole.SERVICE),"current grant works");
        UUID owner=f.manager().owner;f.manager().owner=UUID.randomUUID();h.assertTrue(f.reader().currentScope()==null,"controller owner transfer invalidates grant");f.manager().owner=owner;
        UUID identity=f.manager().identity;f.manager().identity=UUID.randomUUID();h.assertTrue(f.reader().currentScope()==null,"replaced controller invalidates grant");f.manager().identity=identity;
        UUID readerOwner=f.reader().owner;f.reader().owner=UUID.randomUUID();h.assertTrue(registry.cards.authorize(card.card(),f.player(),f.reader().currentScope(),StaffRole.SERVICE),"reader-local metadata does not revoke a valid cinema-role grant");f.reader().owner=readerOwner;
        h.getLevel().setBlock(f.base().above(),h.getLevel().getBlockState(f.base().above()).setValue(CinemaDoorBlock.ACCESS,DoorAccess.PUBLIC),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        h.assertTrue(f.reader().currentScope()==null,"partial door permission edits invalidate the captured door");
        h.getLevel().destroyBlock(f.base(),false);h.assertTrue(registry.door(f.base().asLong())==null,"removal invalidates door generation");
        h.assertTrue(registry.cards.authorize(card.card(),f.player(),scope,StaffRole.SERVICE),"door replacement preserves cinema grant for other valid readers");h.assertTrue(f.reader().currentScope()==null,"removed door cannot actuate with a still-valid cinema card");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void staff_registry_restart_does_not_replay_opening_or_copy_reader_authority(GameTestHelper h){
        var f=fixture(h);var registry=StaffAccessRegistry.get(h.getLevel());var scope=f.reader().currentScope();var card=registry.cards.issue(f.player(),f.manager().owner,scope,StaffRole.ENTRY);registry.pulse(f.binding(),h.getLevel().getGameTime());
        var saved=registry.save(new net.minecraft.nbt.CompoundTag(),h.getLevel().registryAccess());var restored=StaffAccessRegistry.load(saved,h.getLevel().registryAccess());
        h.assertTrue(restored.cards.authorize(card.card(),f.player(),scope,StaffRole.ENTRY),"restart retains current server registrations");h.assertTrue(restored.door(f.base().asLong()).token.equals(f.binding().token),"persistent door generation survives restart");h.assertFalse(restored.door(f.base().asLong()).held(h.getLevel().getGameTime()),"restart never replays an old swipe");
        UUID old=f.reader().identity;f.reader().initialize(f.reader().owner);h.assertFalse(f.reader().identity.equals(old),"physical placement assigns fresh reader identity");h.assertTrue(f.reader().controllerId==null&&f.reader().doorId==null,"placement clears copied pairing authority");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void staff_physical_swipe_uses_registry_and_real_owner_checks(GameTestHelper h){
        h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"offline actor exists only in isolated QA");
        var f=fixture(h);var registry=StaffAccessRegistry.get(h.getLevel());Actor owner=new Actor(h.getLevel(),f.manager().owner),staff=new Actor(h.getLevel(),f.player()),stranger=new Actor(h.getLevel(),UUID.randomUUID());
        for(Actor actor:List.of(owner,staff,stranger))actor.setPos(f.reader().getBlockPos().getCenter());
        f.reader().requireEditor(owner);try{f.reader().requireEditor(staff);h.fail("staff card role must never grant editor ownership");}catch(IllegalArgumentException expected){}
        var grant=registry.cards.issue(staff.getUUID(),owner.getUUID(),f.reader().currentScope(),StaffRole.ENTRY);
        var card=new net.minecraft.world.item.ItemStack(AccessModule.CARD.get());StaffCardItem.stamp(card,grant,"StaffAccessQA");staff.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,card);
        var context=new net.minecraft.world.item.context.UseOnContext(staff,net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.phys.BlockHitResult(f.reader().getBlockPos().getCenter(),Direction.UP,f.reader().getBlockPos(),false));
        h.assertTrue(AccessModule.CARD.get().onItemUseFirst(card,context).consumesAction(),"ordinary card use is routed before reader block interaction");h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,true);
        var stolen=card.copy();stranger.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,stolen);h.assertFalse(StaffCardItem.swipe(stranger,f.reader(),stolen),"copied card in another player's real hand is denied");
        var paper=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);paper.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,card.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA));staff.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,paper);h.assertFalse(StaffCardItem.swipe(staff,f.reader(),paper),"legacy paper with copied card NBT is not a staff card");
        staff.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,card);f.reader().required=StaffRole.SERVICE;h.assertFalse(StaffCardItem.swipe(staff,f.reader(),card),"ENTRY card cannot open SERVICE reader");f.reader().required=StaffRole.ENTRY;
        h.assertFalse(StaffCardItem.swipe(staff,f.reader(),card.copy()),"caller cannot substitute a stack outside the player's actual hands");
        var forged=new net.minecraft.nbt.CompoundTag();forged.putUUID("staffCard",UUID.randomUUID());var forgedCard=new net.minecraft.world.item.ItemStack(AccessModule.CARD.get());forgedCard.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(forged));staff.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,forgedCard);h.assertFalse(StaffCardItem.swipe(staff,f.reader(),forgedCard),"invented NBT token cannot forge registration");
        staff.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,card);staff.setPos(f.reader().getBlockPos().getCenter().add(9,0,0));h.assertFalse(StaffCardItem.swipe(staff,f.reader(),card),"physical swipe must remain local");staff.setPos(f.reader().getBlockPos().getCenter());
        registry.cards.revokeCinema(f.reader().currentScope(),staff.getUUID());h.assertFalse(StaffCardItem.swipe(staff,f.reader(),card),"revocation is effective on the next physical swipe");h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void staff_double_door_survivor_retains_exit_without_old_entry_authority(GameTestHelper h){
        var f=fixture(h);BlockPos partner=f.relative().east(),absolute=h.absolutePos(partner);h.setBlock(partner.below(),Blocks.STONE);
        BlockState state=f.door().defaultBlockState().setValue(CinemaDoorBlock.FACING,Direction.NORTH).setValue(CinemaDoorBlock.HINGE,DoorHingeSide.RIGHT).setValue(CinemaDoorBlock.ACCESS,DoorAccess.STAFF_FRONT);
        for(DoorPart part:DoorPart.values())h.getLevel().setBlock(absolute.above(part.offset()),state.setValue(CinemaDoorBlock.PART,part),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        var registry=StaffAccessRegistry.get(h.getLevel());var leaves=new ArrayList<>(f.binding().leaves);leaves.add(new StaffAccessRegistry.Leaf(absolute.asLong(),"mycinema:cinema_door_metal|north|right|staff_front|staff_front|staff_front"));leaves.sort(Comparator.comparingLong(StaffAccessRegistry.Leaf::pos));
        var pair=new StaffAccessRegistry.Door(UUID.randomUUID(),f.reader().identity,f.reader().getBlockPos().asLong(),leaves);registry.bind(pair);f.reader().doorId=pair.token;f.reader().doorPos=BlockPos.of(pair.base());
        var scope=f.reader().currentScope();h.assertTrue(scope!=null,"double-door pair is authoritative before replacement");var card=registry.cards.issue(f.player(),f.manager().owner,scope,StaffRole.SERVICE);
        h.getLevel().destroyBlock(absolute,false);var survivor=registry.door(f.base().asLong());h.assertTrue(survivor!=null&&!survivor.token.equals(pair.token),"surviving leaf gets a fresh exit-only generation");
        h.assertTrue(registry.cards.authorize(card.card(),f.player(),scope,StaffRole.ENTRY),"removed pair preserves cinema-role employee credentials");h.assertTrue(f.reader().currentScope()==null,"old reader binding cannot authorize survivor entry");
        var actor=h.makeMockPlayer(GameType.SURVIVAL);actor.setPos(f.base().getCenter().add(0,0,-2));h.assertFalse(f.door().setOpen(actor,h.getLevel(),f.base(),true),"outside manual entry remains closed");
        actor.setPos(f.base().getCenter().add(0,0,2));h.assertTrue(f.door().setOpen(actor,h.getLevel(),f.base(),true),"inside can leave through surviving loaded leaf");h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,true);h.succeed();
    }
    private static Fixture additionalTabletDoor(GameTestHelper h,ManagerTile manager,BlockPos pos,BlockPos readerPos){
        var door=CinemaManager.DOOR_METAL.get();h.setBlock(pos.below(),Blocks.STONE);
        var state=door.defaultBlockState().setValue(CinemaDoorBlock.FACING,Direction.NORTH).setValue(CinemaDoorBlock.HINGE,DoorHingeSide.LEFT).setValue(CinemaDoorBlock.ACCESS,DoorAccess.STAFF_FRONT);
        for(DoorPart part:DoorPart.values())h.getLevel().setBlock(h.absolutePos(pos.above(part.offset())),state.setValue(CinemaDoorBlock.PART,part),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        h.setBlock(readerPos,AccessModule.READER.get());StaffReaderTile reader=h.getBlockEntity(readerPos);reader.initialize(manager.owner);
        reader.controllerPos=manager.getBlockPos();reader.controllerId=manager.identity;reader.controllerOwner=manager.owner;
        var binding=new StaffAccessRegistry.Door(UUID.randomUUID(),reader.identity,reader.getBlockPos().asLong(),List.of(new StaffAccessRegistry.Leaf(h.absolutePos(pos).asLong(),"mycinema:cinema_door_metal|north|left|staff_front|staff_front|staff_front")));
        StaffAccessRegistry.get(h.getLevel()).bind(binding);reader.doorPos=h.absolutePos(pos);reader.doorId=binding.token;
        return new Fixture(door,pos,h.absolutePos(pos),reader,manager,binding,manager.owner);
    }
    private static net.minecraft.world.item.ItemStack activateTablet(GameTestHelper h,Actor actor,ManagerTile manager){
        return activateTablet(h,actor,manager,new net.minecraft.world.item.ItemStack(CinemaManager.TABLET.get()));
    }
    private static net.minecraft.world.item.ItemStack activateTablet(GameTestHelper h,Actor actor,ManagerTile manager,net.minecraft.world.item.ItemStack tablet){
        actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,tablet);actor.setPos(manager.getBlockPos().getCenter());actor.setShiftKeyDown(true);
        var context=new net.minecraft.world.item.context.UseOnContext(actor,net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.phys.BlockHitResult(manager.getBlockPos().getCenter(),Direction.UP,manager.getBlockPos(),false));
        h.assertTrue(CinemaManager.TABLET.get().useOn(context)==net.minecraft.world.InteractionResult.SUCCESS,"real local terminal activation accepts its owner");actor.setShiftKeyDown(false);return tablet;
    }
    @GameTest(template="empty",timeoutTicks=100)
    public static void clicked_inventory_tablet_opens_current_and_new_same_cinema_doors(GameTestHelper h){
        h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"tablet actors only run in isolated QA");
        var f=fixture(h);Actor owner=new Actor(h.getLevel(),f.manager().owner),stranger=new Actor(h.getLevel(),UUID.randomUUID());
        var tablet=activateTablet(h,owner,f.manager());owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,net.minecraft.world.item.ItemStack.EMPTY);owner.getInventory().setItem(9,tablet);
        owner.setPos(f.base().getCenter().add(0,0,-1));h.assertTrue(TabletAccess.click(owner,f.base().above(2)),"clicking the top section uses the same inventory authority");h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,true);
        h.assertTrue(owner.getMainHandItem().isEmpty(),"clicked entry does not need a held tablet or swipe");
        var later=additionalTabletDoor(h,f.manager(),new BlockPos(9,1,5),new BlockPos(8,1,3));owner.setPos(later.base().getCenter().add(0,0,-1));
        h.assertTrue(TabletAccess.click(owner,later.base()),"door paired after activation inherits this cinema's tablet access");h.assertBlockProperty(later.relative(),CinemaDoorBlock.OPEN,true);
        h.setBlock(new BlockPos(1,1,10),CinemaManager.MANAGER.get());ManagerTile other=h.getBlockEntity(new BlockPos(1,1,10));other.initializePlacement(owner.getUUID());
        var foreign=additionalTabletDoor(h,other,new BlockPos(5,1,10),new BlockPos(2,1,9));owner.setPos(foreign.base().getCenter());
        h.assertFalse(TabletAccess.click(owner,foreign.base()),"same owner cannot use an A-cinema tablet at B-cinema doors");
        owner.setPos(f.base().getCenter().add(0,0,-1));stranger.setPos(owner.position());stranger.getInventory().setItem(9,tablet.copy());
        h.assertFalse(TabletAccess.click(stranger,f.base()),"stolen or copied tablet in another inventory is denied");
        owner.getInventory().setItem(9,net.minecraft.world.item.ItemStack.EMPTY);owner.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,tablet);
        h.assertTrue(TabletAccess.click(owner,f.base()),"activated offhand tablet is carried inventory");owner.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,net.minecraft.world.item.ItemStack.EMPTY);
        h.assertFalse(TabletAccess.click(owner,f.base()),"removing or dropping the tablet denies the next click");
        var dropped=new net.minecraft.world.entity.item.ItemEntity(h.getLevel(),owner.getX(),owner.getY(),owner.getZ(),tablet.copy());dropped.setPickUpDelay(200);h.getLevel().addFreshEntity(dropped);
        h.assertFalse(TabletAccess.click(owner,f.base()),"nearby item entities do not count as carried tablets");
        h.runAfterDelay(85,()->{h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,false);h.assertBlockProperty(later.relative(),CinemaDoorBlock.OPEN,false);h.succeed();});
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void tablet_activation_revocation_replacement_reload_and_live_permissions(GameTestHelper h){
        h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"tablet actors only run in isolated QA");
        var f=fixture(h);var registry=StaffAccessRegistry.get(h.getLevel());Actor owner=new Actor(h.getLevel(),f.manager().owner);
        var tablet=activateTablet(h,owner,f.manager());var old=tablet.copy();var oldTag=old.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        owner.setPos(f.base().getCenter().add(0,0,-1));h.assertTrue(TabletAccess.click(owner,f.base()),"activated tablet initially opens");
        var saved=registry.save(new net.minecraft.nbt.CompoundTag(),h.getLevel().registryAccess());var restored=StaffAccessRegistry.load(saved,h.getLevel().registryAccess());
        var scope=new TabletAccessBook.Scope(f.manager().identity,f.manager().owner,f.manager().getBlockPos().asLong());
        h.assertTrue(restored.tablets.authorize(oldTag.getUUID("tabletAccess"),owner.getUUID(),scope),"saved registry preserves player and controller activation");
        h.assertFalse(restored.door(f.base().asLong()).held(h.getLevel().getGameTime()),"loading activation does not replay a door opening");
        UUID originalOwner=f.manager().owner;f.manager().owner=UUID.randomUUID();h.assertFalse(TabletAccess.click(owner,f.base()),"owner permission revocation immediately denies tablet");f.manager().owner=originalOwner;
        UUID originalIdentity=f.manager().identity;f.manager().identity=UUID.randomUUID();h.assertFalse(TabletAccess.click(owner,f.base()),"replaced controller cannot inherit activation");f.reader().controllerId=f.manager().identity;
        h.assertFalse(TabletAccess.click(owner,f.base()),"even a newly paired replacement controller needs new tablet activation");f.manager().identity=originalIdentity;f.reader().controllerId=originalIdentity;
        BlockPos originalPos=f.reader().controllerPos;BlockPos unloaded=new BlockPos(200000,80,200000);f.reader().controllerPos=unloaded;
        h.assertFalse(TabletAccess.click(owner,f.base()),"unloaded controller fails closed");h.assertFalse(h.getLevel().hasChunkAt(unloaded),"tablet access never force loads controller");f.reader().controllerPos=originalPos;
        var replacement=activateTablet(h,owner,f.manager());owner.setPos(f.base().getCenter().add(0,0,-1));owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,old);
        h.assertFalse(TabletAccess.click(owner,f.base()),"reactivation revokes every physical copy of old activation");owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,replacement);
        h.assertTrue(TabletAccess.click(owner,f.base()),"new activation remains usable");var revokedCopy=replacement.copy();owner.setShiftKeyDown(true);CinemaManager.TABLET.get().use(h.getLevel(),owner,net.minecraft.world.InteractionHand.MAIN_HAND);owner.setShiftKeyDown(false);
        owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,revokedCopy);h.assertFalse(TabletAccess.click(owner,f.base()),"normal tablet unbind revokes copied tokens too");
        var forged=revokedCopy.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();forged.putUUID("tabletAccess",UUID.randomUUID());revokedCopy.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(forged));h.assertFalse(TabletAccess.click(owner,f.base()),"invented item activation token does not confer access");
        var rebound=activateTablet(h,owner,f.manager());var beforeRebind=rebound.copy();
        h.setBlock(new BlockPos(1,1,10),CinemaManager.MANAGER.get());ManagerTile other=h.getBlockEntity(new BlockPos(1,1,10));other.initializePlacement(owner.getUUID());
        var foreign=additionalTabletDoor(h,other,new BlockPos(5,1,10),new BlockPos(2,1,9));activateTablet(h,owner,other,rebound);owner.setPos(foreign.base().getCenter().add(0,0,-1));
        h.assertTrue(TabletAccess.click(owner,foreign.base()),"rebinding same item activates its new cinema");owner.setPos(f.base().getCenter().add(0,0,-1));h.assertFalse(TabletAccess.click(owner,f.base()),"rebound item no longer opens its former cinema");
        owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,beforeRebind);h.assertFalse(TabletAccess.click(owner,f.base()),"rebinding revokes retained copies of the old cinema activation");
        var legacy=new net.minecraft.world.item.ItemStack(CinemaManager.TABLET.get());var legacyTag=new net.minecraft.nbt.CompoundTag();legacyTag.putUUID("identity",f.manager().identity);legacyTag.putLong("manager",f.manager().getBlockPos().asLong());legacyTag.putString("dimension",h.getLevel().dimension().location().toString());legacy.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(legacyTag));owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,legacy);
        h.assertTrue(TabletItem.boundTo(owner,f.manager()),"legacy held tablet still supports existing authorized remote controls");h.assertFalse(TabletAccess.click(owner,f.base()),"legacy NBT-only binding needs real terminal reactivation for inventory entry");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=40)
    public static void tablet_rebind_at_full_registry_is_atomic(GameTestHelper h){
        h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"capacity actor only runs in isolated QA");
        var f=fixture(h);var registry=StaffAccessRegistry.get(h.getLevel());var original=List.copyOf(registry.tablets.all());
        try{
            Actor owner=new Actor(h.getLevel(),f.manager().owner);var tablet=activateTablet(h,owner,f.manager());
            var old=tablet.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();var oldScope=new TabletAccessBook.Scope(f.manager().identity,f.manager().owner,f.manager().getBlockPos().asLong());
            var filled=new ArrayList<>(registry.tablets.all());
            while(filled.size()<TabletAccessBook.MAX_GRANTS)filled.add(new TabletAccessBook.Grant(UUID.randomUUID(),UUID.randomUUID(),oldScope));registry.tablets.restore(filled);
            h.setBlock(new BlockPos(1,1,10),CinemaManager.MANAGER.get());ManagerTile other=h.getBlockEntity(new BlockPos(1,1,10));other.initializePlacement(owner.getUUID());
            var foreign=additionalTabletDoor(h,other,new BlockPos(5,1,10),new BlockPos(2,1,9));activateTablet(h,owner,other,tablet);
            h.assertTrue(registry.tablets.all().size()==TabletAccessBook.MAX_GRANTS,"same-item rebind keeps full registry within its bound");
            h.assertFalse(registry.tablets.registered(old.getUUID("tabletAccess"),owner.getUUID()),"same-item rebind retires previous cinema token atomically");owner.setPos(foreign.base().getCenter().add(0,0,-1));
            h.assertTrue(TabletAccess.click(owner,foreign.base()),"replacement activation opens new cinema at full capacity");
            var current=tablet.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();var fresh=new net.minecraft.world.item.ItemStack(CinemaManager.TABLET.get());owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,fresh);owner.setPos(f.manager().getBlockPos().getCenter());owner.setShiftKeyDown(true);
            var context=new net.minecraft.world.item.context.UseOnContext(owner,net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.phys.BlockHitResult(f.manager().getBlockPos().getCenter(),Direction.UP,f.manager().getBlockPos(),false));
            h.assertTrue(CinemaManager.TABLET.get().useOn(context)==net.minecraft.world.InteractionResult.FAIL,"full registry rejects an extra fresh tablet for the other cinema");owner.setShiftKeyDown(false);
            h.assertTrue(current.equals(tablet.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag())&&registry.tablets.registered(current.getUUID("tabletAccess"),owner.getUUID()),"failed activation leaves existing tablet and server grant unchanged");
            owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,tablet);owner.setPos(foreign.base().getCenter().add(0,0,-1));h.assertTrue(TabletAccess.click(owner,foreign.base()),"prior activation remains usable after capacity rejection");
        }finally{registry.tablets.restore(original);registry.setDirty();}
        h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=100)
    public static void tablet_requires_explicit_click_and_valid_server_versioned_inventory(GameTestHelper h){
        h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"tablet actors only run in isolated QA");
        var f=fixture(h);Actor owner=new Actor(h.getLevel(),f.manager().owner);var tablet=activateTablet(h,owner,f.manager());
        var original=tablet.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        h.assertTrue(original.getUUID("tabletPlayer").equals(owner.getUUID())&&original.getUUID("identity").equals(f.manager().identity)
                &&original.getUUID("tabletControllerOwner").equals(f.manager().owner)&&original.getInt("tabletAccessVersion")==TabletAccessBook.CURRENT_VERSION,"terminal stamps player, controller, owner and authorization version");
        owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK));owner.getInventory().setItem(9,tablet);
        owner.setPos(f.base().getCenter().add(0,0,-1));
        h.runAfterDelay(15,()->{
            h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,false);
            h.assertFalse(TabletAccess.click(owner,f.base().offset(0,0,1)),"a non-door click does not search nearby managed doors");
            var hit=new net.minecraft.world.phys.BlockHitResult(f.base().getCenter(),Direction.NORTH,f.base(),false);
            h.getLevel().getBlockState(f.base()).useWithoutItem(h.getLevel(),owner,hit);
            h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,true);
            h.assertTrue(owner.getMainHandItem().is(net.minecraft.world.item.Items.STICK),"actual block click accepts tablet in inventory while holding another item");
            for(String key:List.of("tabletAccess","tabletPlayer","identity","tabletControllerOwner")){
                var forged=original.copy();forged.putUUID(key,UUID.randomUUID());tablet.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(forged));
                h.assertFalse(TabletAccess.click(owner,f.base()),"changed "+key+" denies inventory entry");
            }
            for(int version:new int[]{0,TabletAccessBook.CURRENT_VERSION+1}){
                var forged=original.copy();forged.putInt("tabletAccessVersion",version);tablet.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(forged));
                h.assertFalse(TabletAccess.click(owner,f.base()),"unknown item authorization version fails closed");
            }
            var malformed=original.copy();malformed.putString("tabletAccessVersion","1");tablet.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(malformed));
            h.assertFalse(TabletAccess.click(owner,f.base()),"wrong NBT version type is denied");
            var foreignDimension=original.copy();foreignDimension.putString("dimension","minecraft:the_nether");tablet.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(foreignDimension));
            h.assertFalse(TabletAccess.click(owner,f.base()),"same coordinates in another dimension cannot inherit access");
            var changedPos=original.copy();changedPos.putLong("manager",f.manager().getBlockPos().above().asLong());tablet.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(changedPos));
            h.assertFalse(TabletAccess.click(owner,f.base()),"NBT controller position must match the server scope");
            var legacy=original.copy();legacy.remove("tabletAccessVersion");legacy.remove("tabletControllerOwner");tablet.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(legacy));
            h.assertTrue(TabletAccess.click(owner,f.base()),"pre-version server-registered tablet migrates without a new UI step");
            var migrated=tablet.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
            h.assertTrue(migrated.getInt("tabletAccessVersion")==TabletAccessBook.CURRENT_VERSION&&migrated.getUUID("tabletControllerOwner").equals(f.manager().owner),"migration writes server-derived version and owner");
            var notRegistered=legacy.copy();notRegistered.putUUID("tabletAccess",UUID.randomUUID());tablet.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(notRegistered));
            h.assertFalse(TabletAccess.click(owner,f.base()),"legacy-shaped NBT alone cannot migrate or authorize");
            h.assertFalse(tablet.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag().contains("tabletAccessVersion"),"denied legacy item is not mutated");
            tablet.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(original));
            var registry=StaffAccessRegistry.get(h.getLevel());var saved=registry.save(new net.minecraft.nbt.CompoundTag(),h.getLevel().registryAccess());
            for(var raw:saved.getList("tablets",net.minecraft.nbt.Tag.TAG_COMPOUND))((net.minecraft.nbt.CompoundTag)raw).putInt("version",TabletAccessBook.CURRENT_VERSION+1);
            var future=StaffAccessRegistry.load(saved,h.getLevel().registryAccess());h.assertFalse(future.tablets.registered(original.getUUID("tabletAccess"),owner.getUUID()),"unknown persisted authority version fails closed");
            owner.setGameMode(GameType.SPECTATOR);h.assertFalse(TabletAccess.click(owner,f.base()),"spectator cannot use inventory authority");owner.setGameMode(GameType.SURVIVAL);
            BlockPos unbound=new BlockPos(13,1,5);h.setBlock(unbound.below(),Blocks.STONE);
            var unboundState=f.door().defaultBlockState().setValue(CinemaDoorBlock.FACING,Direction.NORTH).setValue(CinemaDoorBlock.ACCESS,DoorAccess.STAFF_FRONT);
            for(DoorPart part:DoorPart.values())h.getLevel().setBlock(h.absolutePos(unbound.above(part.offset())),unboundState.setValue(CinemaDoorBlock.PART,part),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
            owner.setPos(h.absolutePos(unbound).getCenter().add(0,0,-1));h.assertFalse(TabletAccess.click(owner,h.absolutePos(unbound)),"valid manager credential has no authority over an unregistered controlled door");
            owner.setPos(f.base().getCenter().add(0,0,-20));h.assertFalse(TabletAccess.click(owner,f.base()),"inventory access does not add remote door reach");
            owner.setPos(f.base().getCenter().add(0,0,-1));owner.getInventory().setItem(9,net.minecraft.world.item.ItemStack.EMPTY);
            owner.getInventory().setItem(10,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER));owner.getInventory().getItem(10).set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(original));
            h.assertFalse(TabletAccess.click(owner,f.base()),"ordinary paper with copied tablet NBT is not a TMS credential");
            h.runAfterDelay(70,()->{h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,false);h.succeed();});
        });
    }

    @GameTest(template="empty",timeoutTicks=60)
    public static void employee_cinema_roles_survive_other_readers_repair_and_explicit_revocation(GameTestHelper h){
        h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"employee actors only run in isolated QA");
        var f=fixture(h);var registry=StaffAccessRegistry.get(h.getLevel());Actor owner=new Actor(h.getLevel(),f.manager().owner),entry=new Actor(h.getLevel(),UUID.randomUUID()),service=new Actor(h.getLevel(),UUID.randomUUID());
        var original=registry.cards.issue(entry.getUUID(),owner.getUUID(),f.reader().currentScope(),StaffRole.ENTRY);
        var entryCard=new net.minecraft.world.item.ItemStack(AccessModule.CARD.get());StaffCardItem.stamp(entryCard,original,"Entry QA");entry.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,entryCard);
        var later=additionalTabletDoor(h,f.manager(),new BlockPos(9,1,5),new BlockPos(8,1,3));entry.setPos(later.reader().getBlockPos().getCenter());
        h.assertTrue(StaffCardItem.swipe(entry,later.reader(),entryCard),"old card opens a newly installed same-cinema ENTRY reader");
        owner.setPos(later.reader().getBlockPos().getCenter());later.reader().changeRole(owner,later.reader().editRevision.toString(),StaffRole.SERVICE);
        h.assertFalse(StaffCardItem.swipe(entry,later.reader(),entryCard),"reader type still enforces SERVICE against ENTRY card");
        entry.setPos(f.reader().getBlockPos().getCenter());h.assertTrue(StaffCardItem.swipe(entry,f.reader(),entryCard),"reader type change leaves ENTRY access at other doors intact");
        var senior=registry.cards.issue(service.getUUID(),owner.getUUID(),later.reader().currentScope(),StaffRole.SERVICE);
        var serviceCard=new net.minecraft.world.item.ItemStack(AccessModule.CARD.get());StaffCardItem.stamp(serviceCard,senior,"Service QA");service.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,serviceCard);
        for(var reader:List.of(f.reader(),later.reader())){service.setPos(reader.getBlockPos().getCenter());h.assertTrue(StaffCardItem.swipe(service,reader,serviceCard),"SERVICE opens both ENTRY and SERVICE readers in the cinema");}
        owner.setPos(later.base().getCenter().add(0,0,-1));AccessDoors.bind(owner,later.reader(),later.base());
        service.setPos(later.reader().getBlockPos().getCenter());h.assertTrue(StaffCardItem.swipe(service,later.reader(),serviceCard),"re-pairing reader/door does not silently revoke its cinema cards");
        var saved=registry.save(new net.minecraft.nbt.CompoundTag(),h.getLevel().registryAccess());var restored=StaffAccessRegistry.load(saved,h.getLevel().registryAccess());
        h.assertTrue(restored.cards.authorize(original.card(),entry.getUUID(),f.reader().currentScope(),StaffRole.ENTRY),"old server-record grants keep their original type across save/load");
        h.assertTrue(restored.cards.authorize(senior.card(),service.getUUID(),later.reader().currentScope(),StaffRole.SERVICE),"saved issuance reader does not restrict restored same-cinema authority");
        registry.cards.revokeCinema(f.reader().currentScope(),service.getUUID());
        for(var reader:List.of(f.reader(),later.reader())){service.setPos(reader.getBlockPos().getCenter());h.assertFalse(StaffCardItem.swipe(service,reader,serviceCard),"explicit cinema revocation denies all readers and copied tokens immediately");}
        entry.setPos(f.reader().getBlockPos().getCenter());h.assertTrue(StaffCardItem.swipe(entry,f.reader(),entryCard),"revoking another employee preserves this employee");
        // Rebinding a reader to another cinema changes its authority, never the original card's cinema.
        h.setBlock(new BlockPos(1,1,10),CinemaManager.MANAGER.get());ManagerTile other=h.getBlockEntity(new BlockPos(1,1,10));other.initializePlacement(owner.getUUID());
        owner.setPos(f.reader().getBlockPos().getCenter());f.reader().bindController(owner,other);owner.setPos(f.base().getCenter().add(0,0,-1));AccessDoors.bind(owner,f.reader(),f.base());
        entry.setPos(f.reader().getBlockPos().getCenter());h.assertFalse(StaffCardItem.swipe(entry,f.reader(),entryCard),"same-type reader rebound to another cinema rejects original card");
        h.assertTrue(registry.cards.authorize(original.card(),entry.getUUID(),later.reader().currentScope(),StaffRole.ENTRY),"moving a reader preserves the original cinema's server grant");
        var newCinema=registry.cards.issue(entry.getUUID(),owner.getUUID(),f.reader().currentScope(),StaffRole.ENTRY);
        var secondCard=new net.minecraft.world.item.ItemStack(AccessModule.CARD.get());StaffCardItem.stamp(secondCard,newCinema,"Second cinema QA");entry.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,secondCard);
        h.assertTrue(StaffCardItem.swipe(entry,f.reader(),secondCard),"new cinema requires its own real server-issued credential");
        entry.setPos(later.reader().getBlockPos().getCenter());h.assertFalse(StaffCardItem.swipe(entry,later.reader(),secondCard),"second-cinema card cannot replay into the original cinema");h.succeed();
    }

    @GameTest(template="empty",timeoutTicks=40)
    public static void tablet_item_first_routes_shift_door_click_without_unbinding_or_granting_by_type(GameTestHelper h){
        h.assertTrue(Boolean.getBoolean("mycinema.qa")&&h.getLevel().getServer() instanceof GameTestServer,"tablet item routing runs only in isolated QA");
        var f=fixture(h);Actor owner=new Actor(h.getLevel(),f.manager().owner);var tablet=activateTablet(h,owner,f.manager());
        var original=tablet.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        owner.setPos(f.base().getCenter().add(0,0,-1));owner.setShiftKeyDown(true);
        var top=f.base().above(2);var hit=new net.minecraft.world.phys.BlockHitResult(top.getCenter(),Direction.NORTH,top,false);
        var use=new net.minecraft.world.item.context.UseOnContext(owner,net.minecraft.world.InteractionHand.MAIN_HAND,hit);
        CinemaManager.TABLET.get().onItemUseFirst(tablet,use);
        h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,true);
        h.assertTrue(original.equals(tablet.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag()),"Shift door click preserves binding and active token");
        h.assertTrue(TabletAccess.registeredHeld(owner,f.manager()),"Shift opening does not revoke registration");
        for(DoorPart part:DoorPart.values()){
            var pos=f.base().above(part.offset());h.getLevel().setBlock(pos,h.getLevel().getBlockState(pos).setValue(CinemaDoorBlock.OPEN,false),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        }
        owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(CinemaManager.TABLET.get()));
        CinemaManager.TABLET.get().onItemUseFirst(owner.getMainHandItem(),new net.minecraft.world.item.context.UseOnContext(owner,net.minecraft.world.InteractionHand.MAIN_HAND,hit));
        h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,false);
        h.assertTrue(owner.getMainHandItem().get(net.minecraft.core.component.DataComponents.CUSTOM_DATA)==null,"unregistered item gains no authority or binding");
        owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,tablet);owner.setShiftKeyDown(false);
        CinemaManager.TABLET.get().onItemUseFirst(tablet,use);h.assertBlockProperty(f.relative(),CinemaDoorBlock.OPEN,true);
        owner.setShiftKeyDown(true);owner.setPos(f.base().getCenter().add(0,0,-20));
        h.assertTrue(CinemaManager.TABLET.get().onItemUseFirst(tablet,use)==net.minecraft.world.InteractionResult.FAIL,"first-use routing preserves ordinary reach");
        h.assertTrue(original.equals(tablet.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag()),"failed door click does not clear tablet");
        h.succeed();
    }

}
