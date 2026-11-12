package local.cinemamanager.advertising;

import local.cinemamanager.panels.assets.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** Server-produced asset silhouette; bounded saved data is never accepted as an editor collision mask. */
public final class AdvertisingShape {
    public static final AdvertisingShape RECTANGLE=new AdvertisingShape("",0,0,null);
    /** Coordinates in the advertising plane, in blocks with bottom-up vertical orientation. */
    public record LocalRect(double left,double bottom,double right,double top){}
    private record CacheKey(AdvertisingShape shape,AdvertisingGeometry geometry,Direction facing,boolean floor,int u,int v){}
    private static final Map<CacheKey,VoxelShape> CACHE=new LinkedHashMap<>(64,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<CacheKey,VoxelShape> entry){return size()>512;}
    };
    private final String source;private final int imageWidth,imageHeight;private final AssetAlphaMask mask;
    private AdvertisingShape(String source,int width,int height,AssetAlphaMask mask){this.source=source;imageWidth=width;imageHeight=height;this.mask=mask;}
    /** Only AssetStore's committed private metadata can supply an application profile. */
    public static AdvertisingShape fromAsset(String reference,AssetStore.Metadata metadata){
        if(metadata==null||!AssetReference.isAsset(reference)||!metadata.hash().equals(AssetReference.hash(reference))
                ||!metadata.format().equals("PNG")||metadata.alpha()==null||metadata.width()<1||metadata.height()<1
                ||metadata.width()>8192||metadata.height()>8192||(long)metadata.width()*metadata.height()>16L*1024*1024)return RECTANGLE;
        return new AdvertisingShape(AssetReference.normalize(reference),metadata.width(),metadata.height(),metadata.alpha());
    }
    public boolean rectangular(){return mask==null;}
    public String source(){return source;}
    public int imageWidth(){return imageWidth;}public int imageHeight(){return imageHeight;}
    public AssetAlphaMask mask(){return mask;}
    /** Same contain fit as the visible PNG, with no rectangular frame or inset border in silhouette mode. */
    public LocalRect imageRect(AdvertisingGeometry geometry){
        double x=geometry.left()/8.0,y=geometry.bottom()/8.0,w=geometry.widthBlocks(),h=geometry.heightBlocks();
        if(rectangular())return new LocalRect(x,y,x+w,y+h);
        double scale=Math.min(w/imageWidth,h/imageHeight),fittedW=imageWidth*scale,fittedH=imageHeight*scale;
        x+=(w-fittedW)/2;y+=(h-fittedH)/2;return new LocalRect(x,y,x+fittedW,y+fittedH);
    }
    public List<LocalRect> bodyRectangles(AdvertisingGeometry geometry){
        LocalRect fitted=imageRect(geometry);if(rectangular())return List.of(fitted);
        double width=fitted.right()-fitted.left(),height=fitted.top()-fitted.bottom();
        return mask.rectangles().stream().map(r->new LocalRect(fitted.left()+width*r.left()/32,
                fitted.top()-height*r.bottom()/32,fitted.left()+width*r.right()/32,fitted.top()-height*r.top()/32)).toList();
    }
    public VoxelShape worldShape(AdvertisingGeometry geometry,BlockPos anchor,Direction facing,boolean floor){
        VoxelShape shape=Shapes.empty();
        for(LocalRect r:bodyRectangles(geometry))shape=Shapes.or(shape,Shapes.create(AdvertisingGeometry.worldBox(anchor,facing,r.left(),r.bottom(),floor?.4375:.875,r.right(),r.top(),floor?.5625:1)));
        return floor?Shapes.or(shape,Shapes.create(geometry.base(anchor,facing))):shape;
    }
    /** At most 128 body boxes plus one independent foot, clipped to the identity/proxy cell. */
    public VoxelShape shape(AdvertisingGeometry geometry,BlockPos anchor,Direction facing,boolean floor,BlockPos cell){
        if(!geometry.contains(anchor,facing,cell))return Shapes.empty();
        Direction right=facing.getCounterClockWise();BlockPos delta=cell.subtract(anchor);
        int u=delta.getX()*right.getStepX()+delta.getZ()*right.getStepZ(),v=delta.getY();
        CacheKey key=new CacheKey(this,geometry,facing,floor,u,v);
        synchronized(CACHE){VoxelShape found=CACHE.get(key);if(found!=null)return found;}
        BlockPos localCell=BlockPos.ZERO.relative(right,u).above(v);VoxelShape shape=Shapes.empty();
        for(LocalRect r:bodyRectangles(geometry))shape=Shapes.or(shape,clipped(AdvertisingGeometry.worldBox(BlockPos.ZERO,facing,r.left(),r.bottom(),floor?.4375:.875,r.right(),r.top(),floor?.5625:1),localCell));
        if(floor)shape=Shapes.or(shape,clipped(geometry.base(BlockPos.ZERO,facing),localCell));
        synchronized(CACHE){CACHE.put(key,shape);}return shape;
    }
    private static VoxelShape clipped(AABB box,BlockPos cell){AABB unit=new AABB(cell);return box.intersects(unit)?Shapes.create(box.intersect(unit).move(-cell.getX(),-cell.getY(),-cell.getZ())):Shapes.empty();}
    public CompoundTag save(){
        CompoundTag tag=new CompoundTag();if(rectangular())return tag;
        tag.putInt("version",1);tag.putString("source",source);tag.putInt("width",imageWidth);tag.putInt("height",imageHeight);tag.putIntArray("rows",mask.rows());return tag;
    }
    /** Saved-world or server-to-client data only. Missing/malformed legacy data safely keeps the rectangle. */
    public static AdvertisingShape read(CompoundTag tag){
        try{
            String source=tag.getString("source");int w=tag.getInt("width"),h=tag.getInt("height");
            if(tag.getInt("version")!=1||source.length()!=AssetReference.PREFIX.length()+64||!source.equals(AssetReference.normalize(source))||w<1||h<1||w>8192||h>8192||(long)w*h>16L*1024*1024
                    ||!tag.contains("rows",Tag.TAG_INT_ARRAY)||tag.getIntArray("rows").length!=32)return RECTANGLE;
            int[] rows=tag.getIntArray("rows");AssetAlphaMask mask=AssetAlphaMask.ofRows(rows);
            if(!Arrays.equals(rows,mask.rows()))return RECTANGLE;
            return new AdvertisingShape(source,w,h,mask);
        }catch(RuntimeException malformed){return RECTANGLE;}
    }
    @Override public boolean equals(Object other){return other instanceof AdvertisingShape shape&&source.equals(shape.source)&&imageWidth==shape.imageWidth&&imageHeight==shape.imageHeight&&Objects.equals(mask,shape.mask);}
    @Override public int hashCode(){return Objects.hash(source,imageWidth,imageHeight,mask);}
}
