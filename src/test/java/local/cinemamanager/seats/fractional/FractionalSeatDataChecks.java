package local.cinemamanager.seats.fractional;

import java.util.*;

/** New recovery model tests; run by the authorized build worker, never counted as run from source inspection. */
public final class FractionalSeatDataChecks {
    private static int count;
    private static void check(boolean b,String message){count++;if(!b)throw new AssertionError(message);}
    private static void reject(Runnable task){count++;try{task.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("invalid input accepted");}
    private static int index(int x,int y,int z){return(x*64+y)*64+z;}
    public static void main(String[]args){
        var uid=new UUID(0,1);var other=new UUID(0,2);var anchor=new FractionalSeatData.Anchor(.5,0,.5,0);
        int first=index(10,20,30),second=index(40,20,30);int[] raw={first,first+2};
        var a=new FractionalSeatData.Contribution(uid,anchor,raw,FractionalSeatGeometryProfile.HIGHBACK_150);raw[0]=0;
        check(a.runsCopy()[0]==first,"input copied");a.runsCopy()[0]=2;check(a.runsCopy()[0]==first,"output copied");
        var b=new FractionalSeatData.Contribution(other,anchor,new int[]{second,second+1});var data=FractionalSeatData.of(List.of(a,b));
        check(data.voxelsCopy().cardinality()==3,"union");data.voxelsCopy().clear();check(data.voxelsCopy().cardinality()==3,"union copy");
        check(data.with(a)==data,"idempotent placement");check(data.without(uid).owner(other).orElseThrow().equals(b),"neighbour preserved");
        check(data.without(uid).without(uid).equals(data.without(uid)),"idempotent deletion");check(data.without(uid).without(other).isEmpty(),"last owner removed");
        check(!a.equals(new FractionalSeatData.Contribution(uid,anchor,a.runsCopy())),"profile belongs to identity");
        check(FractionalSeatGeometryProfile.fromHash(a.geometry().modelSha256())==FractionalSeatGeometryProfile.HIGHBACK_150,"selected hash");
        check(!FractionalSeatGeometryProfile.HIGHBACK_150.validCell(anchor,0,-1,0),"no lower home");
        check(FractionalSeatGeometryProfile.HIGHBACK_150.canonicalBounds().maxY()==1.5,"selected total height");
        double y=20.5/64,z=30.5/64;
        check(data.firstOwnerOnRay(-1,y,z,2,y,z).orElseThrow().equals(uid),"first forward UID");
        check(data.firstOwnerOnRay(2,y,z,-1,y,z).orElseThrow().equals(other),"first reverse UID");
        check(data.firstOwnerOnRay((41-5e-10)/64,y,z,0,y,z).orElseThrow().equals(other),"near-negative-boundary retained");
        check(data.firstOwnerOnRay(10.5/64,y,z,10.5/64,y,z).isEmpty(),"zero ray rejected");
        check(data.firstOwnerOnRay(Double.NaN,y,z,2,y,z).isEmpty(),"nonfinite ray rejected");
        check(data.firstOwnerOnRay(-1,0,0,2,0,0).isEmpty(),"gap stays empty");
        var overlap=new FractionalSeatData.Contribution(other,anchor,new int[]{first,first+1});
        check(FractionalSeatData.of(List.of(a,overlap)).firstOwnerOnRay(-1,y,z,2,y,z).isEmpty(),"ambiguous owner fails closed");
        for(int[] bad:new int[][]{new int[0],{0},{-1,2},{2,2},{3,2},{0,262145},{0,1,1,3},{0,2,1,3},{3,4,0,1},{0,262144},new int[8194]})reject(()->new FractionalSeatData.Contribution(uid,anchor,bad));
        reject(()->new FractionalSeatData.Anchor(Double.NaN,0,0,0));reject(()->new FractionalSeatData.Anchor(0,0,0,4));
        reject(()->FractionalSeatGeometryProfile.fromHash("unknown"));reject(()->FractionalSeatData.of(List.of(a,a)));
        reject(()->FractionalSeatData.of(List.of(new FractionalSeatData.Contribution(uid,anchor,new int[]{0,131072}),new FractionalSeatData.Contribution(other,anchor,new int[]{131072,262144}))));
        for(int turn=0;turn<4;turn++){var k=new FractionalSeatData.Anchor(0,83.5,0,turn);check(FractionalSeatGeometryProfile.HIGHBACK_150.validCell(k,0,84,0),"half-floor upper cell accepted");check(!FractionalSeatGeometryProfile.HIGHBACK_150.validCell(k,0,82,0),"half-floor lower cell refused");}
        System.out.println("FractionalSeatDataChecks: "+count+" passed");
    }
}
