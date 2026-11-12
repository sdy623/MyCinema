package local.cinemamanager.seats;

import java.util.UUID;

public final class ContinuousSeatChecks {
    private static final String ACTOR=UUID.randomUUID().toString(),CONTROLLER=UUID.randomUUID().toString(),HALL=UUID.randomUUID().toString();
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void reject(Runnable operation,String message){try{operation.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){}}
    private static ContinuousSeatState start(int row,int number,int direction,int end,long revision){return ContinuousSeatState.start(ACTOR,CONTROLLER,"minecraft:overworld",44,HALL,"Lower",row,number,direction,end,revision);}
    public static void main(String[] args){
        SeatRegistry registry=new SeatRegistry();ContinuousSeatState first=start(3,7,1,9,0);
        var a=first.bind(registry,12345,"minecraft:oak_stairs");
        check(a.created()&&a.seat().number()==7&&a.state().next()==8&&registry.revision()==1,"successful click consumes exactly one number");
        var skip=a.state().bind(registry,12345,"minecraft:oak_stairs");
        check(!skip.created()&&skip.state().equals(a.state())&&registry.revision()==1,"existing position skips without consuming number/revision");
        var b=skip.state().bind(registry,-4444,"minecraft:birch_stairs");
        var c=b.state().bind(registry,901234,"minecraft:stone_stairs");
        check(c.seat().number()==9&&!c.state().active()&&c.state().next()==9&&registry.seats().size()==3,"inclusive end stops without a guessed next position");
        reject(()->c.state().bind(registry,888,"minecraft:oak_stairs"),"completed range refuses another click");
        ContinuousSeatState nextRow=c.state().nextRow(registry.revision());
        check(nextRow.row()==4&&nextRow.next()==7&&nextRow.start()==7&&nextRow.active(),"only explicit next row resets the original start");
        check(nextRow.bind(registry,777,"minecraft:oak_stairs").seat().row()==4,"next row registers selected block only");
        SeatRegistry descending=new SeatRegistry();var down=start(1,3,-1,1,0).bind(descending,100,"minecraft:oak_stairs");
        down=down.state().bind(descending,90,"minecraft:oak_stairs");down=down.state().bind(descending,50,"minecraft:oak_stairs");
        check(!down.state().active()&&descending.seats().stream().map(SeatRegistry.Seat::number).toList().equals(java.util.List.of(3,2,1)),"descending numbers follow click order");
        for(int direction:new int[]{1,-1}){
            SeatRegistry edge=new SeatRegistry();int number=direction==1?999:1;
            var stop=start(1,number,direction,0,0).bind(edge,1,"minecraft:oak_stairs");
            check(!stop.state().active()&&stop.state().next()==number,"implicit 1/999 bound stops without overflow");
        }
        SeatRegistry duplicate=new SeatRegistry();duplicate.put(0,"",HALL,"lower",1,5,1,SeatRegistry.Status.AVAILABLE);
        ContinuousSeatState collision=start(1,5,1,7,1);
        reject(()->collision.bind(duplicate,2,"minecraft:oak_stairs"),"case-insensitive duplicate identity rejected");
        check(collision.next()==5&&duplicate.revision()==1&&duplicate.seats().size()==1,"duplicate identity is atomic");
        reject(()->first.bind(registry,111,"minecraft:oak_stairs"),"stale card revision cannot register");
        check(first.next()==7,"stale failure preserves cursor");
        check(ContinuousSeatState.decode(c.state().encode()).equals(c.state()),"completed state survives disk JSON round trip");
        ContinuousSeatState restored=ContinuousSeatState.decode(a.state().encode());SeatRegistry restoredRegistry=SeatRegistry.decode(registry.encode());
        check(restored.next()==8&&restored.start()==7,"active cursor survives disk JSON round trip");
        reject(()->restored.bind(restoredRegistry,999,"minecraft:oak_stairs"),"restored stale state still respects registry revision");
        first.requireScope(ACTOR,CONTROLLER,"minecraft:overworld",44);
        reject(()->first.requireScope(UUID.randomUUID().toString(),CONTROLLER,"minecraft:overworld",44),"wrong actor");
        reject(()->first.requireScope(ACTOR,UUID.randomUUID().toString(),"minecraft:overworld",44),"replaced controller");
        reject(()->first.requireScope(ACTOR,CONTROLLER,"minecraft:the_nether",44),"different dimension");
        reject(()->first.requireScope(ACTOR,CONTROLLER,"minecraft:overworld",45),"different controller position");
        reject(()->start(999,1,1,0,0).nextRow(0),"row 999 never wraps");
        reject(()->start(1,4,1,3,0),"ascending end behind start");reject(()->start(1,4,-1,5,0),"descending end ahead of start");
        reject(()->start(1,1,0,0,0),"zero direction");reject(()->start(1,1,2,0,0),"direction must be one step");
        reject(()->start(0,1,1,0,0),"row 0");reject(()->start(1,1000,1,0,0),"seat 1000");
        for(String bad:new String[]{"{}","null","[]",first.encode().replace("\"next\":7","\"next\":7.5"),first.encode().replace("\"next\":7","\"next\":\"7\""),first.encode().replace("\"next\":7","\"next\":0"),first.encode().replace("\"direction\":1","\"direction\":0"),first.encode().replace("\"active\":true","\"active\":false"),first.encode().replace("\"active\":true","\"active\":\"true\""),first.encode().replace("\"revision\":0","\"revision\":-1"),first.encode().replace("\"revision\":0","\"revision\":9223372036854775808")})reject(()->ContinuousSeatState.decode(bad),"invalid saved state");
        reject(()->SeatNetwork.parse("{\"direction\":1.5}"),"fractional direction packet");reject(()->SeatNetwork.parse("{\"end\":\"12\"}"),"string end packet");
        reject(()->SeatNetwork.parse("{\"card\":\"not-a-card\"}"),"invalid exact-card token");
        System.out.println("ContinuousSeatChecks passed");
    }
}
