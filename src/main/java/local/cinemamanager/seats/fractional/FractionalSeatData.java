package local.cinemamanager.seats.fractional;

import java.util.*;

/** Newly reimplemented immutable ownership boundary. It never uses one enclosing chair/block AABB as solid. */
public final class FractionalSeatData {
    public static final int VERSION=2, RESOLUTION=64, VOXELS=64*64*64, MAX_OWNERS=4, MAX_RUN_INTS=8192;
    public static final String MODEL_SHA256="ca470795c3ab0a30672c59fcbcf5f0d0e5570c87e6688d4d8bdb57fb23f2fd7d";
    public static final FractionalSeatData EMPTY=new FractionalSeatData(Map.of());
    public record Anchor(double x,double y,double z,int turns){
        public Anchor{
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>30_000_000
                ||Math.abs(z)>30_000_000||Math.abs(y)>4096||turns<0||turns>3)
                throw new IllegalArgumentException("Invalid chair anchor");
        }
    }
    public static final class Contribution {
        private final UUID uid;
        private final Anchor anchor;
        private final FractionalSeatGeometryProfile geometry;
        private final int[] runs;
        public Contribution(UUID uid,Anchor anchor,int[] runs){this(uid,anchor,runs,FractionalSeatGeometryProfile.ORIGINAL);}
        public Contribution(UUID uid,Anchor anchor,int[] input,FractionalSeatGeometryProfile geometry){
            this.uid=Objects.requireNonNull(uid);this.anchor=Objects.requireNonNull(anchor);this.geometry=Objects.requireNonNull(geometry);
            if(input==null||input.length==0||input.length>MAX_RUN_INTS||(input.length&1)!=0)throw invalid("Bad run count");
            runs=input.clone();int last=-1;
            for(int i=0;i<runs.length;i+=2){
                if(runs[i]<0||runs[i+1]>VOXELS||runs[i]>=runs[i+1]||runs[i]<=last)throw invalid("Noncanonical run");
                last=runs[i+1];
            }
            if(runs.length==2&&runs[0]==0&&runs[1]==VOXELS)throw invalid("Full-cell chair prohibited");
        }
        public UUID uid(){return uid;}
        public Anchor anchor(){return anchor;}
        public FractionalSeatGeometryProfile geometry(){return geometry;}
        public int[] runsCopy(){return runs.clone();}
        public boolean occupied(int index){
            if(index<0||index>=VOXELS)return false;
            int lo=0,hi=runs.length/2;
            while(lo<hi){int mid=(lo+hi)>>>1;if(runs[2*mid]<=index)lo=mid+1;else hi=mid;}
            return lo>0&&index<runs[2*(lo-1)+1];
        }
        /** Allocation-free AABB query of this exact 1/64 voxel solid, with positive-volume contact semantics. */
        public boolean intersectsLocalBox(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){
            if(!Double.isFinite(minX)||!Double.isFinite(minY)||!Double.isFinite(minZ)
                ||!Double.isFinite(maxX)||!Double.isFinite(maxY)||!Double.isFinite(maxZ))return true;
            if(minX>maxX||minY>maxY||minZ>maxZ)return true;
            if(minX==maxX||minY==maxY||minZ==maxZ||maxX<=0||maxY<=0||maxZ<=0||minX>=1||minY>=1||minZ>=1)return false;
            int x0=Math.max(0,(int)Math.floor(minX*64)),x1=Math.min(64,(int)Math.ceil(maxX*64));
            int y0=Math.max(0,(int)Math.floor(minY*64)),y1=Math.min(64,(int)Math.ceil(maxY*64));
            int z0=Math.max(0,(int)Math.floor(minZ*64)),z1=Math.min(64,(int)Math.ceil(maxZ*64));
            int first=(x0*64+y0)*64+z0,last=((x1-1)*64+y1-1)*64+z1;
            if(runs[runs.length-1]<=first||runs[0]>=last)return false;
            for(int x=x0;x<x1;x++)for(int y=y0;y<y1;y++){
                int start=(x*64+y)*64+z0,end=(x*64+y)*64+z1,lo=0,hi=runs.length/2;
                while(lo<hi){int mid=(lo+hi)>>>1;if(runs[mid*2+1]<=start)lo=mid+1;else hi=mid;}
                if(lo<runs.length/2&&runs[lo*2]<end)return true;
            }
            return false;
        }
        void unionInto(BitSet bits){for(int i=0;i<runs.length;i+=2)bits.set(runs[i],runs[i+1]);}
        @Override public boolean equals(Object obj){return obj instanceof Contribution c&&uid.equals(c.uid)&&anchor.equals(c.anchor)&&geometry==c.geometry&&Arrays.equals(runs,c.runs);}
        @Override public int hashCode(){return Objects.hash(uid,anchor,geometry,Arrays.hashCode(runs));}
    }
    private final Map<UUID,Contribution> owners;
    private FractionalSeatData(Map<UUID,Contribution> owners){this.owners=Collections.unmodifiableMap(owners);}
    public static FractionalSeatData of(Collection<Contribution> values){
        if(values==null||values.size()>MAX_OWNERS)throw invalid("Owner cap exceeded");
        TreeMap<UUID,Contribution> map=new TreeMap<>();
        for(Contribution value:values)if(value==null||map.putIfAbsent(value.uid(),value)!=null)throw invalid("Duplicate/null owner");
        if(map.isEmpty())return EMPTY;
        FractionalSeatData result=new FractionalSeatData(map);
        if(result.voxelsCopy().cardinality()==VOXELS)throw invalid("Full-cell union prohibited");
        return result;
    }
    public Collection<Contribution> owners(){return owners.values();}
    public Optional<Contribution> owner(UUID uid){return Optional.ofNullable(owners.get(uid));}
    public boolean isEmpty(){return owners.isEmpty();}
    public FractionalSeatData with(Contribution value){
        Objects.requireNonNull(value);if(value.equals(owners.get(value.uid())))return this;
        TreeMap<UUID,Contribution> copy=new TreeMap<>(owners);copy.put(value.uid(),value);return of(copy.values());
    }
    public FractionalSeatData without(UUID uid){
        Objects.requireNonNull(uid);if(!owners.containsKey(uid))return this;
        TreeMap<UUID,Contribution> copy=new TreeMap<>(owners);copy.remove(uid);return of(copy.values());
    }
    public BitSet voxelsCopy(){BitSet b=new BitSet(VOXELS);owners.values().forEach(c->c.unionInto(b));return b;}
    public boolean validAt(int x,int y,int z){for(Contribution c:owners.values())if(!c.geometry().validCell(c.anchor(),x,y,z))return false;return true;}
    /** The first multiply-owned voxel returns no UID. Ray miss and ambiguity are both fail-closed. */
    public Optional<UUID> firstOwnerOnRay(double sx,double sy,double sz,double ex,double ey,double ez){
        double[] start={sx,sy,sz},d={ex-sx,ey-sy,ez-sz};double enter=0,leave=1;
        if(d[0]==0&&d[1]==0&&d[2]==0)return Optional.empty();
        for(int a=0;a<3;a++){
            if(!Double.isFinite(start[a])||!Double.isFinite(d[a]))return Optional.empty();
            if(d[a]==0){if(start[a]<0||start[a]>=1)return Optional.empty();}
            else{double t0=-start[a]/d[a],t1=(1-start[a])/d[a];enter=Math.max(enter,Math.min(t0,t1));leave=Math.min(leave,Math.max(t0,t1));}
        }
        if(enter>=leave)return Optional.empty();
        int[] cell=new int[3],step=new int[3];double[] next=new double[3],dt=new double[3];
        for(int a=0;a<3;a++){
            double p=(start[a]+d[a]*enter)*64;
            cell[a]=Math.max(0,Math.min(63,d[a]<0?(int)Math.ceil(p)-1:(int)Math.floor(p)));
            step[a]=d[a]>0?1:d[a]<0?-1:0;
            if(step[a]==0){next[a]=dt[a]=Double.POSITIVE_INFINITY;}
            else{next[a]=((cell[a]+(step[a]>0?1:0))/64.0-start[a])/d[a];dt[a]=1/(64*Math.abs(d[a]));}
        }
        for(int n=0;n<196;n++){
            if(cell[0]<0||cell[0]>63||cell[1]<0||cell[1]>63||cell[2]<0||cell[2]>63)break;
            int index=(cell[0]*64+cell[1])*64+cell[2];UUID hit=null;
            for(Contribution c:owners.values())if(c.occupied(index)){if(hit!=null)return Optional.empty();hit=c.uid();}
            if(hit!=null)return Optional.of(hit);
            double t=Math.min(next[0],Math.min(next[1],next[2]));if(t>=leave)break;
            for(int a=0;a<3;a++)if(next[a]==t){cell[a]+=step[a];next[a]+=dt[a];}
        }
        return Optional.empty();
    }
    @Override public boolean equals(Object obj){return obj instanceof FractionalSeatData d&&owners.equals(d.owners);}
    @Override public int hashCode(){return owners.hashCode();}
    private static IllegalArgumentException invalid(String value){return new IllegalArgumentException(value);}
}
