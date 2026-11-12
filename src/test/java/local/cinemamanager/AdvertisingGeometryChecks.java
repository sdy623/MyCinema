package local.cinemamanager;

import local.cinemamanager.advertising.AdvertisingGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

/** Offline geometry checks using native shapes, with no Minecraft world, server or graphics device. */
public final class AdvertisingGeometryChecks {
    private static int checks;
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private static void rejects(Runnable action,String reason){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;}check(rejected,reason);}
    private static void near(double actual,double expected,String reason){check(Math.abs(actual-expected)<1e-9,reason);}
    private static double volume(VoxelShape shape){return shape.toAabbs().stream().mapToDouble(b->b.getXsize()*b.getYsize()*b.getZsize()).sum();}

    private static void verify(AdvertisingGeometry geometry,BlockPos anchor,Direction facing,boolean floor){
        List<BlockPos> cells=geometry.cells(anchor,facing);
        check(new HashSet<>(cells).size()==cells.size(),"all reserved cells are unique");
        check(cells.contains(anchor),"the original anchor always remains inside signed geometry");
        check(cells.size()<=17*17,"fractional 16m edges reserve at most 17 by 17 cells");
        int expectedColumns=Math.floorDiv(geometry.right()-1,8)-Math.floorDiv(geometry.left(),8)+1;
        int expectedRows=Math.floorDiv(geometry.top()-1,8)-Math.floorDiv(geometry.bottom(),8)+1;
        check(cells.size()==expectedColumns*expectedRows,"signed edge floor division reserves exact intersected cells");
        VoxelShape assembled=Shapes.empty();
        for(BlockPos cell:cells){
            check(geometry.contains(anchor,facing,cell),"constant-time membership agrees with exact reservation");
            check(!geometry.contains(anchor,facing,cell.relative(facing)),"parallel neighbor plane is never reserved");
            VoxelShape local=geometry.shape(anchor,facing,floor,cell);
            check(!local.isEmpty(),"every proxy contains physical body or foot");
            AABB b=local.bounds();
            check(b.minX>=0&&b.minY>=0&&b.minZ>=0&&b.maxX<=1&&b.maxY<=1&&b.maxZ<=1,"proxy shapes cannot spill into foreign cells");
            assembled=Shapes.or(assembled,local.move(cell.getX(),cell.getY(),cell.getZ()));
        }
        VoxelShape expected=geometry.worldShape(anchor,facing,floor);
        check(!Shapes.joinIsNotEmpty(assembled,expected,BooleanOp.NOT_SAME),"root and proxies exactly reconstruct body/foot with neither gaps nor phantom collision");
        double bodyVolume=geometry.widthBlocks()*geometry.heightBlocks()*.125;
        double footExtra=floor?geometry.widthBlocks()*.125*(.5625-.125):0;
        near(volume(assembled),bodyVolume+footExtra,"actual volume is thin body plus only the projecting foot");
        AABB body=geometry.body(anchor,facing,floor);
        near(facing.getAxis()==Direction.Axis.Z?body.getZsize():body.getXsize(),.125,"body is 1/8m thick in all four orientations");
        near(body.getYsize(),geometry.heightBlocks(),"height remains exact in eighths");
        near(facing.getAxis()==Direction.Axis.Z?body.getXsize():body.getZsize(),geometry.widthBlocks(),"width remains exact in eighths");
        Vec3 center=body.getCenter();
        near(geometry.distanceSquared(anchor,facing,floor,center),0,"a point on the actual display is in interaction range");
        near(geometry.distanceSquared(anchor,facing,floor,center.add(0,geometry.heightBlocks()/2+3,0)),9,"range follows expanded display extents");
        check(AdvertisingGeometry.read(geometry.save()).equals(geometry),"all signed edges survive NBT round trip");
    }

    public static void main(String[] args){
        AdvertisingGeometry origin=AdvertisingGeometry.DEFAULT;
        for(String edge:List.of("left","right","bottom","top"))for(int amount:List.of(1,8)){
            AdvertisingGeometry changed=origin.edge(edge,amount);
            check(changed.edge(edge,-amount).equals(origin),"eighth-step and 1m edge changes round trip");
            near(edge.equals("left")||edge.equals("right")?changed.widthBlocks():changed.heightBlocks(),1+amount/8.0,"quick and precise controls share exact eighth units");
        }
        rejects(()->origin.edge("left",-8),"shrinking cannot erase anchor intersection");
        rejects(()->origin.edge("right",-8),"zero width rejected");
        rejects(()->origin.edge("top",121),"over-16m height rejected");
        rejects(()->origin.edge("right",Integer.MAX_VALUE),"overflow delta rejected");
        rejects(()->origin.edge("unknown",1),"unrecognized edge rejected");
        rejects(()->new AdvertisingGeometry(1,0,8,8),"anchor cannot be excluded to the left");
        rejects(()->new AdvertisingGeometry(0,1,8,8),"anchor cannot be excluded below");
        rejects(()->new AdvertisingGeometry(-8,0,8,8),"zero-area anchor intersection rejected");
        rejects(()->new AdvertisingGeometry(Integer.MIN_VALUE,0,128,128),"unbounded signed edge rejected");
        rejects(()->new AdvertisingGeometry(0,0,129,128),"hard 16m width limit enforced");
        rejects(()->new AdvertisingGeometry(0,0,128,129),"hard 16m height limit enforced");
        for(int version:List.of(0,2,Integer.MAX_VALUE)){CompoundTag tag=origin.save();tag.putInt("version",version);check(AdvertisingGeometry.read(tag).equals(origin),"unsupported saved versions use bounded default");}
        CompoundTag invalid=origin.save();invalid.putInt("width",Integer.MAX_VALUE);check(AdvertisingGeometry.read(invalid).equals(origin),"malformed NBT cannot create enormous world reservations");

        List<AdvertisingGeometry> fixtures=List.of(origin,new AdvertisingGeometry(0,0,1,1),new AdvertisingGeometry(-1,-1,9,9),
                new AdvertisingGeometry(-7,-7,8,8),new AdvertisingGeometry(-8,-8,17,25),new AdvertisingGeometry(0,0,128,128),
                new AdvertisingGeometry(-127,-127,128,128),new AdvertisingGeometry(-1,-1,128,128));
        for(Direction facing:Direction.Plane.HORIZONTAL)for(boolean floor:new boolean[]{false,true})
            for(AdvertisingGeometry fixture:fixtures)verify(fixture,new BlockPos(-23,57,-39),facing,floor);

        // Exhaust every valid single-axis interval, including fractional edges at negative world coordinates.
        for(int width=1;width<=128;width++)for(int left=1-width;left<=0;left++){
            AdvertisingGeometry geometry=new AdvertisingGeometry(left,0,width,8);
            for(Direction facing:Direction.Plane.HORIZONTAL){
                BlockPos anchor=new BlockPos(-17,3,15);
                List<BlockPos> cells=geometry.cells(anchor,facing);
                check(cells.size()==Math.floorDiv(left+width-1,8)-Math.floorDiv(left,8)+1,"all signed intervals have exact coverage");
                check(geometry.contains(anchor,facing,anchor),"all legal intervals preserve identity cell");
                check(!geometry.contains(anchor,facing,cells.getFirst().relative(facing.getCounterClockWise().getOpposite())),"interval lower endpoint is exclusive outside reservation");
                check(!geometry.contains(anchor,facing,cells.getLast().relative(facing.getCounterClockWise())),"interval upper endpoint is exclusive outside reservation");
            }
        }
        for(Direction facing:Direction.Plane.HORIZONTAL){
            BlockPos anchor=BlockPos.ZERO;
            AABB wall=origin.body(anchor,facing,false);
            double nearest=facing.getAxis()==Direction.Axis.Z?(facing==Direction.NORTH?wall.maxZ:1-wall.minZ):(facing==Direction.WEST?wall.maxX:1-wall.minX);
            near(nearest,1,"wall slab ends exactly against clicked backing face");
            VoxelShape floor=origin.worldShape(anchor,facing,true);
            AdvertisingGeometry smallest=new AdvertisingGeometry(0,0,1,1);
            AABB smallBody=smallest.body(anchor,facing,true),smallFoot=smallest.base(anchor,facing);
            double faceBody=facing.getAxis()==Direction.Axis.Z?(facing==Direction.NORTH?smallBody.minZ:smallBody.maxZ):(facing==Direction.WEST?smallBody.minX:smallBody.maxX);
            double faceFoot=facing.getAxis()==Direction.Axis.Z?(facing==Direction.NORTH?smallFoot.minZ:smallFoot.maxZ):(facing==Direction.WEST?smallFoot.minX:smallFoot.maxX);
            near(faceFoot,faceBody,"minimum-height printed face stays in front of the rear-only foot");
            double probeX=.5,probeZ=.5;
            if(facing==Direction.NORTH)probeZ=.8;else if(facing==Direction.SOUTH)probeZ=.2;
            else if(facing==Direction.WEST)probeX=.8;else probeX=.2;
            VoxelShape emptyAboveFoot=Shapes.box(probeX-.05,.2,probeZ-.05,probeX+.05,.9,probeZ+.05);
            check(!Shapes.joinIsNotEmpty(floor,emptyAboveFoot,BooleanOp.AND),"foot bounds do not turn clear space above foot into collision");
            VoxelShape added=Shapes.join(origin.edge("right",1).worldShape(anchor,facing,true),floor,BooleanOp.ONLY_FIRST);
            near(volume(added),(.125*.125)+(.125*.125*.4375),"eighth expansion checks only newly occupied thin material");
        }
        System.out.println("PASS: "+checks+" advertising geometry assertions; signed eighth edges, all facings, exact thin proxy shapes, 16m bounds and NBT.");
    }
}
