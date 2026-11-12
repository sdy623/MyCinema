package local.cinemamanager.panels.assets;

import java.util.*;

/** Immutable conservative PNG occupancy. Every nonzero-alpha pixel covers its complete grid footprint. */
public final class AssetAlphaMask {
    public static final int RESOLUTION=32,MAX_RECTANGLES=128;
    public record Rect(int left,int top,int right,int bottom){}
    private final int[] rows;
    private final List<Rect> rectangles;
    private AssetAlphaMask(int[] rows,List<Rect> rectangles){this.rows=rows;this.rectangles=List.copyOf(rectangles);}
    public static AssetAlphaMask ofRows(int[] source){
        if(source==null||source.length!=RESOLUTION)throw new IllegalArgumentException("asset_invalid_alpha");
        int[] rows=source.clone();boolean nonempty=false;for(int row:rows)nonempty|=row!=0;
        if(!nonempty)throw new IllegalArgumentException("asset_empty_alpha");
        List<Rect> rects=merge(rows);
        // Coarsening only adds material; it cannot remove a visible source pixel.
        for(int step=2;rects.size()>MAX_RECTANGLES;step*=2){
            int[] expanded=new int[RESOLUTION];
            for(int y=0;y<RESOLUTION;y+=step)for(int x=0;x<RESOLUTION;x+=step){
                int bits=(int)(((1L<<step)-1)<<x);boolean occupied=false;
                for(int yy=y;yy<y+step;yy++)occupied|=(rows[yy]&bits)!=0;
                if(occupied)for(int yy=y;yy<y+step;yy++)expanded[yy]|=bits;
            }
            rows=expanded;rects=merge(rows);
        }
        return new AssetAlphaMask(rows,rects);
    }
    private static List<Rect> merge(int[] rows){
        List<Rect> complete=new ArrayList<>();Map<Long,Rect> previous=Map.of();
        for(int y=0;y<RESOLUTION;y++){
            Map<Long,Rect> next=new LinkedHashMap<>();
            for(int x=0;x<RESOLUTION;){
                if((rows[y]&(1<<x))==0){x++;continue;}
                int left=x++;while(x<RESOLUTION&&(rows[y]&(1<<x))!=0)x++;
                long key=((long)left<<32)|x;Rect before=previous.get(key);
                next.put(key,new Rect(left,before==null?y:before.top(),x,y+1));
            }
            for(var entry:previous.entrySet())if(!next.containsKey(entry.getKey()))complete.add(entry.getValue());
            previous=next;
        }
        complete.addAll(previous.values());return complete;
    }
    public int[] rows(){return rows.clone();}
    public List<Rect> rectangles(){return rectangles;}
    public boolean occupied(int x,int y){return x>=0&&x<RESOLUTION&&y>=0&&y<RESOLUTION&&(rows[y]&(1<<x))!=0;}
    @Override public boolean equals(Object other){return other instanceof AssetAlphaMask mask&&Arrays.equals(rows,mask.rows);}
    @Override public int hashCode(){return Arrays.hashCode(rows);}
}
