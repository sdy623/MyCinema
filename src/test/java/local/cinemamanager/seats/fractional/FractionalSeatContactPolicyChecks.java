package local.cinemamanager.seats.fractional;

import java.util.*;
import java.util.function.Consumer;
import static local.cinemamanager.seats.fractional.FractionalSeatContactPolicy.*;

/** Pure numeric and exact-RLE tests; no Minecraft bootstrap. */
public final class FractionalSeatContactPolicyChecks {
    private static int checks;
    private static final class Fixture {
        boolean selected=true,markerProfile=true,standing=true;double scale=1,width=(double).6f,height=(double)1.8f;
        Point floor=new Point(15.86,70,21.3),marker=new Point(15.86,70.455,21.3),passenger=marker,attachment=new Point(0,.6,0);
        Point feet=new Point(passenger.x(),passenger.y()-.6,passenger.z());Bounds body=box(feet,width,height);
        Sample sample(){return new Sample(selected,markerProfile,standing,scale,floor,marker,passenger,attachment,feet,width,height,body);}
        void depth(double depth){floor=new Point(1.5,0,1.5);marker=new Point(1.5,.455-EPS/2,1.5);passenger=marker;
            feet=new Point(1.5,-depth,1.5);attachment=new Point(0,passenger.y()-feet.y(),0);body=box(feet,width,height);}
    }
    private static Bounds box(Point p,double w,double h){return new Bounds(p.x()-w/2,p.y(),p.z()-w/2,p.x()+w/2,p.y()+h,p.z()+w/2);}
    private static void expect(String n,Consumer<Fixture> edit,Failure expected){Fixture f=new Fixture();edit.accept(f);check(validate(f.sample())==expected,n+": "+validate(f.sample()));}
    private static void check(boolean ok,String n){checks++;if(!ok)throw new AssertionError(n);}
    public static void main(String[] args){
        expect("standard float dimensions",f->{},Failure.NONE);
        expect("wrong model",f->f.selected=false,Failure.MODEL);expect("marker flags",f->f.markerProfile=false,Failure.MARKER);
        expect("wrong pose",f->f.standing=false,Failure.POSE);expect("tiny non-1 scale",f->f.scale=Math.nextUp(1.0),Failure.SCALE);
        expect("scaled",f->f.scale=2,Failure.SCALE);expect("dimensions",f->f.width=.61,Failure.DIMENSIONS);
        expect("attachment",f->f.attachment=new Point(0,.601,0),Failure.ATTACHMENT);
        expect("centering",f->{f.feet=new Point(f.feet.x()+.001,f.feet.y(),f.feet.z());f.body=box(f.feet,f.width,f.height);},Failure.CENTERING);
        expect("exact cap",f->f.depth(MAX_CONTACT_DEPTH+EPS),Failure.NONE);
        expect("one representable step past cap",f->f.depth(Math.nextUp(MAX_CONTACT_DEPTH+EPS)),Failure.CONTACT_DEPTH);
        expect("deeper body",f->f.body=new Bounds(f.body.minX(),f.body.minY()-.01,f.body.minZ(),f.body.maxX(),f.body.maxY()-.01,f.body.maxZ()),Failure.CONTACT_DEPTH);
        expect("NaN",f->f.scale=Double.NaN,Failure.NONFINITE);expect("Inf",f->f.feet=new Point(0,Double.POSITIVE_INFINITY,0),Failure.NONFINITE);
        expect("inverted",f->f.body=new Bounds(2,0,2,1,1,1),Failure.DIMENSIONS);
        UUID uid=UUID.randomUUID();int v=(32*64+32)*64+32;
        var one=new FractionalSeatData.Contribution(uid,new FractionalSeatData.Anchor(.5,0,.5,0),new int[]{v,v+1},FractionalSeatGeometryProfile.HIGHBACK_150);
        check(!one.intersectsLocalBox(0,0,0,.5,1,1),"exact face touch");check(one.intersectsLocalBox(0,0,0,Math.nextUp(.5),1,1),"tiny overlap");
        check(!one.intersectsLocalBox(-2,-2,-2,-1,-1,-1),"negative outside");check(!one.intersectsLocalBox(1,1,1,2,2,2),"positive outside");
        check(one.intersectsLocalBox(Double.NaN,0,0,1,1,1),"NaN bounds fail closed");check(one.intersectsLocalBox(0,0,0,Double.POSITIVE_INFINITY,1,1),"Inf bounds fail closed");
        check(one.intersectsLocalBox(1,0,0,0,1,1),"inverted bounds fail closed");check(!one.intersectsLocalBox(.5,.5,.5,.5,.6,.6),"zero volume");
        Random r=new Random(150145);BitSet bits=new BitSet(FractionalSeatData.VOXELS);for(int i=0;i<160;i++)bits.set(r.nextInt(FractionalSeatData.VOXELS));
        List<Integer> runs=new ArrayList<>();for(int at=bits.nextSetBit(0);at>=0;){int end=bits.nextClearBit(at);runs.add(at);runs.add(end);at=bits.nextSetBit(end);}
        var owner=new FractionalSeatData.Contribution(uid,new FractionalSeatData.Anchor(.5,0,.5,0),runs.stream().mapToInt(Integer::intValue).toArray(),FractionalSeatGeometryProfile.HIGHBACK_150);
        for(int n=0;n<1000;n++){double[] lo=new double[3],hi=new double[3];for(int a=0;a<3;a++){double x=r.nextDouble()*2-.5,y=r.nextDouble()*2-.5;lo[a]=Math.min(x,y);hi[a]=Math.max(x,y);}
            boolean oracle=false;for(int bit=bits.nextSetBit(0);bit>=0;bit=bits.nextSetBit(bit+1)){double x=(bit/4096)/64.0,y=(bit/64%64)/64.0,z=(bit%64)/64.0;
                if(x<hi[0]&&x+1.0/64>lo[0]&&y<hi[1]&&y+1.0/64>lo[1]&&z<hi[2]&&z+1.0/64>lo[2]){oracle=true;break;}}
            check(owner.intersectsLocalBox(lo[0],lo[1],lo[2],hi[0],hi[1],hi[2])==oracle,"occupied voxel equivalence "+n);}
        System.out.println("FractionalSeatContactPolicyChecks passed: "+checks);
    }
    private FractionalSeatContactPolicyChecks(){}
}
