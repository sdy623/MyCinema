package local.cinemamanager.seats.fractional;

import java.util.*;
import net.minecraft.core.BlockPos;

/** One complete expected/replacement UID footprint. A caller may not silently transfer geometry/identity. */
public record FractionalSeatFootprint(UUID uid,Map<BlockPos,FractionalSeatData.Contribution> cells){
    public static final int MAX_CELLS=27;
    public FractionalSeatFootprint{
        Objects.requireNonNull(uid);if(cells==null||cells.size()>MAX_CELLS)throw new IllegalArgumentException("Bad footprint size");
        Map<BlockPos,FractionalSeatData.Contribution> copy=new HashMap<>();
        FractionalSeatData.Anchor anchor=null;FractionalSeatGeometryProfile profile=null;
        for(var e:cells.entrySet()){
            BlockPos p=Objects.requireNonNull(e.getKey()).immutable();var c=Objects.requireNonNull(e.getValue());
            if(!uid.equals(c.uid())||anchor!=null&&!anchor.equals(c.anchor())||profile!=null&&profile!=c.geometry()
                ||!c.geometry().validCell(c.anchor(),p.getX(),p.getY(),p.getZ()))throw new IllegalArgumentException("Inconsistent footprint");
            anchor=c.anchor();profile=c.geometry();copy.put(p,c);
        }
        cells=Map.copyOf(copy);
    }
    public static FractionalSeatFootprint empty(UUID uid){return new FractionalSeatFootprint(uid,Map.of());}
}
