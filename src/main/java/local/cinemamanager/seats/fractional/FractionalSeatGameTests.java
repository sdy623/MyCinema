package local.cinemamanager.seats.fractional;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.*;

/** Native test sources for the registered implementation; require a separate QA world and explicit execution. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class FractionalSeatGameTests {
    private static int index(int x,int y,int z){return(x*64+y)*64+z;}
    private static FractionalSeatData.Contribution point(UUID uid,FractionalSeatData.Anchor anchor,int x,int y,int z){int p=index(x,y,z);return new FractionalSeatData.Contribution(uid,anchor,new int[]{p,p+1},FractionalSeatGeometryProfile.HIGHBACK_150);}
    @GameTest(template="empty",timeoutTicks=80)
    public static void fractional_registered_shared_owner_slab_restore(GameTestHelper h){
        BlockPos base=h.absolutePos(new BlockPos(4,2,4)),upper=base.above();var level=h.getLevel();
        level.setBlockAndUpdate(base,FractionalSeatHost.ANDESITE_BOTTOM_SLAB);level.setBlockAndUpdate(upper,Blocks.AIR.defaultBlockState());
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();var anchor=new FractionalSeatData.Anchor(base.getX()+.5,base.getY()+.5,base.getZ()+.5,0);
        var ac=point(a,anchor,10,40,10);var bc=point(b,anchor,40,44,40);var au=point(a,anchor,30,20,30);
        var af=new FractionalSeatFootprint(a,Map.of(base,ac,upper,au));var bf=new FractionalSeatFootprint(b,Map.of(base,bc));
        h.assertTrue(FractionalSeatCells.installWithVerifiedSlabHost(level,af)==2,"two cells installed");
        h.assertTrue(FractionalSeatCells.installWithVerifiedSlabHost(level,bf)==1,"shared owner installed");
        var tile=(FractionalSeatTile)level.getBlockEntity(base);
        h.assertTrue(tile.data().owners().size()==2&&tile.host().equals(FractionalSeatHost.ANDESITE_BOTTOM_SLAB),"exact floor retained");
        h.assertFalse(level.noCollision(new AABB(base.getX()+.2,base.getY()+.1,base.getZ()+.2,base.getX()+.3,base.getY()+.4,base.getZ()+.3)),"real lower slab collision");
        h.assertTrue(FractionalSeatCells.installWithVerifiedSlabHost(level,af)==0,"repeat install no writes");
        FractionalSeatCells.remove(level,af);
        h.assertTrue(level.getBlockState(upper).isAir(),"upper final-owner cell restored");
        h.assertTrue(((FractionalSeatTile)level.getBlockEntity(base)).data().owner(b).orElseThrow().equals(bc),"neighbour exact bytes preserved");
        h.assertTrue(FractionalSeatCells.remove(level,af)==0,"repeat removal no writes");
        FractionalSeatCells.remove(level,bf);h.assertTrue(level.getBlockState(base).equals(FractionalSeatHost.ANDESITE_BOTTOM_SLAB),"last removal exact slab restoration");
        h.succeed();
    }
    @GameTest(template="empty",timeoutTicks=80)
    public static void fractional_nbt_invalid_payload_keeps_floor(GameTestHelper h){
        BlockPos p=h.absolutePos(new BlockPos(4,2,4));var level=h.getLevel();level.setBlockAndUpdate(p,FractionalSeatHost.ANDESITE_BOTTOM_SLAB);
        UUID uid=UUID.randomUUID();var anchor=new FractionalSeatData.Anchor(p.getX()+.5,p.getY()+.5,p.getZ()+.5,0);
        var part=point(uid,anchor,10,40,10);var footprint=new FractionalSeatFootprint(uid,Map.of(p,part));
        FractionalSeatCells.installWithVerifiedSlabHost(level,footprint);var tile=(FractionalSeatTile)level.getBlockEntity(p);
        CompoundTag saved=tile.getUpdateTag(level.registryAccess());
        var decoded=FractionalSeatNbt.read(saved,p);h.assertTrue(decoded.owner(uid).orElseThrow().equals(part),"UID/profile/mask roundtrip");
        CompoundTag bad=saved.copy();bad.putInt("fractional_version",999);tile.loadAdditional(bad,level.registryAccess());
        h.assertFalse(tile.hasValidData(),"unknown NBT version refused");h.assertTrue(tile.collisionShape().bounds().maxY==.5,"invalid chair cannot erase floor");
        tile.loadAdditional(saved,level.registryAccess());h.assertTrue(tile.hasValidData(),"verified payload reload");
        level.setBlockAndUpdate(p,level.getBlockState(p).setValue(FractionalSeatBlock.HOST_MATERIAL,FractionalSeatHost.Material.DEEPSLATE));
        h.assertFalse(tile.hasValidData(),"material/state mismatch fails closed");
        h.assertTrue(tile.collisionShape().bounds().maxY==.5,"mismatched chair still has floor");
        h.succeed();
    }
}
