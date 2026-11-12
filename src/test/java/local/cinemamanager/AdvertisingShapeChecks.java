package local.cinemamanager;

import local.cinemamanager.advertising.*;
import local.cinemamanager.panels.assets.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** Native collision verification without a world; reconstruction, contain fit and conservative profile limits. */
public final class AdvertisingShapeChecks {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void near(double value,double expected,String message){check(Math.abs(value-expected)<1e-8,message);}
    private static double volume(VoxelShape shape){return shape.toAabbs().stream().mapToDouble(b->b.getXsize()*b.getYsize()*b.getZsize()).sum();}
    private static AdvertisingShape profile(int[] rows,int width,int height){return AdvertisingShape.fromAsset(AssetReference.ofHash("f".repeat(64)),new AssetStore.Metadata("f".repeat(64),100,"PNG",width,height,Set.of(UUID.randomUUID()),AssetAlphaMask.ofRows(rows)));}
    public static void main(String[] args){
        int[] ring=new int[32];for(int y=0;y<32;y++)ring[y]=y<8||y>=24?-1:0xff0000ff;
        AdvertisingShape shape=profile(ring,32,32);check(!shape.rectangular(),"private asset occupancy produces silhouette");check(shape.equals(AdvertisingShape.read(shape.save())),"exact profile and digest survive bounded NBT");
        check(AdvertisingShape.read(new CompoundTag()).rectangular(),"old worlds safely retain rectangular geometry");
        CompoundTag invalid=shape.save();invalid.putString("source","https://example.test/image.png");check(AdvertisingShape.read(invalid).rectangular(),"URL is never shape authority");
        invalid=shape.save();invalid.putString("source"," "+shape.source());check(AdvertisingShape.read(invalid).rectangular(),"per-cell profile source has exact bounded canonical syntax");
        invalid=shape.save();invalid.putInt("width",Integer.MAX_VALUE);check(AdvertisingShape.read(invalid).rectangular(),"unbounded dimensions are rejected");
        invalid=shape.save();invalid.putIntArray("rows",new int[33]);check(AdvertisingShape.read(invalid).rectangular(),"unbounded client/disk row data is rejected");
        invalid=shape.save();invalid.putIntArray("rows",new int[32]);check(AdvertisingShape.read(invalid).rectangular(),"invisible empty shape cannot erase the board");
        int[] fragmented=new int[32];for(int y=0;y<32;y++)fragmented[y]=y%2==0?0x55555555:0xaaaaaaaa;
        invalid=shape.save();invalid.putIntArray("rows",fragmented);check(AdvertisingShape.read(invalid).rectangular(),"noncanonical oversized box data cannot bypass the server profile budget");
        check(AdvertisingShape.fromAsset(AssetReference.ofHash("e".repeat(64)),new AssetStore.Metadata("f".repeat(64),100,"PNG",32,32,Set.of(),AssetAlphaMask.ofRows(ring))).rectangular(),"profile cannot be rebound to a different immutable asset");
        for(Direction facing:Direction.Plane.HORIZONTAL)for(boolean floor:new boolean[]{false,true}){
            for(AdvertisingGeometry g:List.of(AdvertisingGeometry.DEFAULT,new AdvertisingGeometry(-1,-1,9,17),new AdvertisingGeometry(-9,-9,26,26),new AdvertisingGeometry(-127,-127,128,128),new AdvertisingGeometry(0,0,1,1))){
                BlockPos anchor=new BlockPos(-23,57,-39);VoxelShape assembled=Shapes.empty();boolean sawEmpty=false;
                for(BlockPos cell:g.cells(anchor,facing)){
                    VoxelShape local=shape.shape(g,anchor,facing,floor,cell);sawEmpty|=local.isEmpty();
                    check(local==shape.shape(g,anchor,facing,floor,cell),"repeated cell collision uses bounded cached shape");
                    if(!local.isEmpty()){
                        AABB b=local.bounds();check(b.minX>=0&&b.minY>=0&&b.minZ>=0&&b.maxX<=1&&b.maxY<=1&&b.maxZ<=1,"profile collision is clipped inside its owned cell");
                    }
                    assembled=Shapes.or(assembled,local.move(cell.getX(),cell.getY(),cell.getZ()));
                }
                VoxelShape expected=shape.worldShape(g,anchor,facing,floor);
                check(!Shapes.joinIsNotEmpty(assembled,expected,BooleanOp.NOT_SAME),"every root and proxy exactly reconstructs board plus independent foot");
                check(!Shapes.joinIsNotEmpty(expected,g.worldShape(anchor,facing,floor),BooleanOp.ONLY_FIRST),"silhouette collision never exceeds prior rectangular sign");
                var fit=shape.imageRect(g);near(fit.right()-fit.left(),fit.top()-fit.bottom(),"PNG and physical profile use identical contain aspect ratio");
                double w=fit.right()-fit.left(),h=fit.top()-fit.bottom();
                near(fit.left()+w/2,(g.left()+g.right())/16.0,"letterboxing remains centered horizontally");
                near(fit.bottom()+h/2,(g.bottom()+g.top())/16.0,"letterboxing remains centered vertically");
                if(!floor)near(volume(expected),w*h*.75*.125,"clear alpha ring center remains passable; collision is not its AABB");
                else check(!Shapes.joinIsNotEmpty(Shapes.create(g.base(anchor,facing)),expected,BooleanOp.ONLY_FIRST),"unmasked foot is always present in full even below alpha holes");
                if(g.width()==128)check(sawEmpty,"transparent central cells remain reservations with no phantom collision");
            }
        }
        AdvertisingGeometry g=new AdvertisingGeometry(0,0,8,16);AdvertisingShape wide=profile(ring,64,32);var fit=wide.imageRect(g);
        near(fit.left(),0,"wide image has full board width");near(fit.right(),1,"wide image full board width end");near(fit.bottom(),.75,"wide image letterbox below retained");near(fit.top(),1.25,"wide image letterbox above retained");
        near(volume(wide.worldShape(g,BlockPos.ZERO,Direction.NORTH,false)),.5*.75*.125,"actual fit bounds drive collision instead of canvas AABB");
        for(Direction facing:Direction.Plane.HORIZONTAL){
            VoxelShape same=AdvertisingShape.RECTANGLE.worldShape(g,BlockPos.ZERO,facing,true);
            check(!Shapes.joinIsNotEmpty(same,g.worldShape(BlockPos.ZERO,facing,true),BooleanOp.NOT_SAME),"safe default preserves old geometry exactly");
        }
        System.out.println("PASS: "+checks+" native advertising PNG collision, footprint, caching, NBT, contain-fit and foot checks.");
    }
}
