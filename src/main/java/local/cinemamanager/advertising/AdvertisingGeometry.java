package local.cinemamanager.advertising;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.*;

/** Signed edges in exact eighth-block units, in the sign's local display plane. */
public record AdvertisingGeometry(int left,int bottom,int width,int height) {
    public static final int UNITS=8,MAX_UNITS=128;
    public static final AdvertisingGeometry DEFAULT=new AdvertisingGeometry(0,0,8,8);
    public AdvertisingGeometry {
        if(width<1||height<1||width>MAX_UNITS||height>MAX_UNITS||left>0||bottom>0
                ||left<=-MAX_UNITS||bottom<=-MAX_UNITS||left+width<=0||bottom+height<=0)
            throw new IllegalArgumentException("mycinema.ad.geometry_error");
    }
    public int right(){return left+width;} public int top(){return bottom+height;}
    public double widthBlocks(){return width/8.0;} public double heightBlocks(){return height/8.0;}
    public CompoundTag save(){CompoundTag t=new CompoundTag();t.putInt("version",1);t.putInt("left",left);t.putInt("bottom",bottom);t.putInt("width",width);t.putInt("height",height);return t;}
    public static AdvertisingGeometry read(CompoundTag t){try{return t.getInt("version")==1?new AdvertisingGeometry(t.getInt("left"),t.getInt("bottom"),t.getInt("width"),t.getInt("height")):DEFAULT;}catch(RuntimeException e){return DEFAULT;}}
    public AdvertisingGeometry edge(String edge,int delta){return switch(edge){
        case "left"->new AdvertisingGeometry(left-delta,bottom,width+delta,height);
        case "right"->new AdvertisingGeometry(left,bottom,width+delta,height);
        case "bottom"->new AdvertisingGeometry(left,bottom-delta,width,height+delta);
        case "top"->new AdvertisingGeometry(left,bottom,width,height+delta);
        default->throw new IllegalArgumentException("mycinema.ad.geometry_error");};}
    public List<BlockPos> cells(BlockPos anchor,Direction facing){
        if(!facing.getAxis().isHorizontal())throw new IllegalArgumentException();
        List<BlockPos> out=new ArrayList<>();Direction right=facing.getCounterClockWise();
        for(int v=Math.floorDiv(bottom,8);v<=Math.floorDiv(top()-1,8);v++)
            for(int u=Math.floorDiv(left,8);u<=Math.floorDiv(right()-1,8);u++)out.add(anchor.relative(right,u).above(v));
        return List.copyOf(out);
    }
    /** Constant-time membership, including signed fractional edges and the original identity cell. */
    public boolean contains(BlockPos anchor,Direction facing,BlockPos cell){
        if(!facing.getAxis().isHorizontal())return false;
        Direction right=facing.getCounterClockWise();BlockPos delta=cell.subtract(anchor);
        if(delta.getX()*facing.getStepX()+delta.getZ()*facing.getStepZ()!=0)return false;
        int u=delta.getX()*right.getStepX()+delta.getZ()*right.getStepZ(),v=delta.getY();
        return u>=Math.floorDiv(left,8)&&u<=Math.floorDiv(right()-1,8)
                &&v>=Math.floorDiv(bottom,8)&&v<=Math.floorDiv(top()-1,8);
    }
    /** Local model coordinates: u right, v up, depth measured from the outward face. */
    static AABB worldBox(BlockPos anchor,Direction facing,double u0,double v0,double d0,double u1,double v1,double d1){
        Direction right=facing.getCounterClockWise(),back=facing.getOpposite();
        Vec3 base=Vec3.atLowerCornerOf(anchor).add(.5,0,.5);
        Vec3 a=base.add(right.getStepX()*(u0-.5)+back.getStepX()*(d0-.5),v0,right.getStepZ()*(u0-.5)+back.getStepZ()*(d0-.5));
        Vec3 b=base.add(right.getStepX()*(u1-.5)+back.getStepX()*(d1-.5),v1,right.getStepZ()*(u1-.5)+back.getStepZ()*(d1-.5));
        return new AABB(a,b);
    }
    public AABB body(BlockPos anchor,Direction facing,boolean floor){return worldBox(anchor,facing,left/8.0,bottom/8.0,floor?.4375:.875,right()/8.0,top()/8.0,floor?.5625:1);}
    public AABB base(BlockPos anchor,Direction facing){return worldBox(anchor,facing,left/8.0,bottom/8.0,.4375,right()/8.0,bottom/8.0+.125,1);}
    public AABB bounds(BlockPos anchor,Direction facing,boolean floor){AABB body=body(anchor,facing,floor);return floor?body.minmax(base(anchor,facing)):body;}
    /** Exact world-space union, so collision checks never fill the space above the foot. */
    public VoxelShape worldShape(BlockPos anchor,Direction facing,boolean floor){VoxelShape body=Shapes.create(body(anchor,facing,floor));return floor?Shapes.or(body,Shapes.create(base(anchor,facing))):body;}
    private static VoxelShape clipped(AABB box,BlockPos cell){AABB unit=new AABB(cell);if(!box.intersects(unit))return Shapes.empty();return Shapes.create(box.intersect(unit).move(-cell.getX(),-cell.getY(),-cell.getZ()));}
    public VoxelShape shape(BlockPos anchor,Direction facing,boolean floor,BlockPos cell){VoxelShape s=clipped(body(anchor,facing,floor),cell);return floor?Shapes.or(s,clipped(base(anchor,facing),cell)):s;}
    public double distanceSquared(BlockPos anchor,Direction facing,boolean floor,Vec3 point){AABB b=bounds(anchor,facing,floor);double x=Math.max(b.minX,Math.min(b.maxX,point.x)),y=Math.max(b.minY,Math.min(b.maxY,point.y)),z=Math.max(b.minZ,Math.min(b.maxZ,point.z));return point.distanceToSqr(x,y,z);}
}
