package local.cinemamanager.seats;

import java.util.*;

public final class SeatRegistryChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void reject(Runnable work,String reason){try{work.run();throw new AssertionError(reason);}catch(IllegalArgumentException expected){}}
    public static void main(String[] args){
        SeatRegistry registry=new SeatRegistry();String hall=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        SeatRegistry.Seat a=registry.put(0,"",hall,"Lower",1,1,100,SeatRegistry.Status.AVAILABLE);
        reject(()->registry.put(0,"",hall,"Lower",1,2,101,SeatRegistry.Status.AVAILABLE),"stale create");
        reject(()->registry.put(1,"",hall,"lower",1,1,101,SeatRegistry.Status.AVAILABLE),"case-folded duplicate index");
        reject(()->registry.put(1,"",other,"Upper",2,2,100,SeatRegistry.Status.AVAILABLE),"duplicate block position across halls");
        check(registry.revision()==1&&registry.seats().size()==1,"rejected changes are atomic");
        SeatRegistry.Seat b=registry.put(1,"",hall,"Upper",1,1,101,SeatRegistry.Status.MAINTENANCE);
        registry.put(2,"",hall,"Upper",2,2,102,SeatRegistry.Status.DISABLED);
        SeatRegistry.Count total=registry.counts().stream().filter(c->c.zone().isEmpty()).findFirst().orElseThrow();
        check(total.total()==3&&total.available()==1&&total.maintenance()==1&&total.disabled()==1,"hall status counts");
        check(registry.counts().stream().anyMatch(c->c.zone().equals("Upper")&&c.row()==0&&c.total()==2),"zone count");
        check(registry.counts().stream().anyMatch(c->c.zone().equals("Upper")&&c.row()==2&&c.disabled()==1),"row count");
        SeatRegistry copy=SeatRegistry.decode(registry.encode());check(copy.seats().equals(registry.seats())&&copy.revision()==3,"stable IDs + statuses survive reload");
        SeatRegistry.Seat changed=copy.put(3,a.id(),hall,"Lower",1,2,100,SeatRegistry.Status.DISABLED);check(changed.id().equals(a.id()),"edit keeps stable identity");
        reject(()->copy.remove(3,b.id()),"stale delete");copy.remove(4,b.id());check(copy.revision()==5&&copy.seats().size()==2,"exact delete");
        reject(()->SeatRegistry.decode(registry.encode().replace("\"revision\":3","\"revision\":3.25")),"fractional revision");
        reject(()->SeatRegistry.decode(registry.encode().replace("\"row\":1","\"row\":1.5")),"fractional row");
        reject(()->SeatRegistry.decode(registry.encode().replace(a.id(),b.id())),"duplicate saved IDs");
        reject(()->new GuidePreset("Hall","Upper",8,2,0,GuidePreset.Arrow.LEFT),"reversed range");
        reject(()->new GuidePreset("Hall","Upper",1,3,2,GuidePreset.Arrow.LEFT),"seat requires single row");
        GuidePreset guide=new GuidePreset("Hall","Upper",8,12,0,GuidePreset.Arrow.UP_RIGHT);check(guide.arrowText().equals("↗")&&guide.firstRow()==8&&guide.lastRow()==12,"manual row-range preset");
        reject(()->registry.put(3,"",hall,"Bad\nzone",1,3,200,SeatRegistry.Status.AVAILABLE),"control characters rejected");
        SeatRegistry full=new SeatRegistry();for(int i=0;i<SeatRegistry.MAX_SEATS;i++)full.put(i,"",hall,"Zone",i/999+1,i%999+1,1000+i,SeatRegistry.Status.AVAILABLE);
        reject(()->full.put(full.revision(),"",hall,"Other",9,9,9000,SeatRegistry.Status.AVAILABLE),"bounded seat count");
        boolean[][] bits=BitTagRaster.raster(new GuidePreset("Hall","Upper",999,999,999,GuidePreset.Arrow.RIGHT));check(bits.length==16&&bits[0].length==16,"bounded 16 bit raster");
        boolean[][] left=BitTagRaster.raster(new GuidePreset("","",1,999,0,GuidePreset.Arrow.LEFT)),right=BitTagRaster.raster(new GuidePreset("","",1,999,0,GuidePreset.Arrow.RIGHT));check(left[13][6]&&!left[13][8]&&right[13][8]&&!right[13][6],"arrows retain manual direction");
        GuidePresetChecks.run();
        RowBatchChecks.run();
        RowSelectionStateChecks.run();
        System.out.println("SeatRegistryChecks passed");
    }
}
