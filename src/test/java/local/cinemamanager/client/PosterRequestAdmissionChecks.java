package local.cinemamanager.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Deterministic rate/admission checks. Queue fixtures simulate timely server replies, not network execution. */
public final class PosterRequestAdmissionChecks {
    private static int checks;
    private static void check(boolean yes,String reason){checks++;if(!yes)throw new AssertionError(reason);}
    private static long ms(long value){return TimeUnit.MILLISECONDS.toNanos(value);}

    public static void main(String[] args){
        var gate=new PosterRequestAdmission();
        check(gate.take(0,0),"initial request enters immediately");
        for(int attempt=0;attempt<100;attempt++)check(!gate.take(ms(99),0),"repeated frames cannot consume a second dispatch early");
        check(gate.take(ms(100),0),"next request enters at exact pacing boundary");
        check(!gate.take(ms(200),16),"sixteen actual in-flight requests preserve the existing wire bound");
        check(gate.take(ms(200),15),"local congestion does not consume permission or the next available dispatch");
        check(!gate.take(ms(50),0),"a regressed clock does not trigger a burst");
        check(gate.take(ms(10_000),0)&&!gate.take(ms(10_000),0),"resuming after a stall cannot release accumulated burst credit");

        gate=new PosterRequestAdmission();
        List<Long> sends=new ArrayList<>();
        for(long millis=0;millis<10_000;millis++)if(gate.take(ms(millis),0))sends.add(millis);
        for(long start=0;start<9_000;start+=17){
            long from=start;long count=sends.stream().filter(at->at>=from&&at<from+1_000).count();
            check(count<=10,"every sliding second stays below the unchanged server limit of 24");
        }
        check(sends.size()==100,"constant demand makes steady progress without a retry storm");

        // The production queue is FIFO and shares this gate between initial redirect hops and refreshes.
        gate=new PosterRequestAdmission();
        var queue=new ArrayDeque<String>();
        for(int i=0;i<16;i++)queue.addLast("refresh "+i);
        queue.addLast("initial redirect");
        List<String> completed=new ArrayList<>();
        for(int millis=0;millis<5_000&&!queue.isEmpty();millis+=50)
            if(gate.take(ms(millis),0))completed.add(queue.removeFirst());
        check(queue.isEmpty()&&completed.size()==17,"sixteen simultaneous refreshes plus an initial hop fit the existing five-second deadline with timely replies");
        check(completed.getLast().equals("initial redirect"),"new initial requests do not jump ahead of older refreshes");

        gate=new PosterRequestAdmission();
        int[] hops=new int[5];queue.clear();
        for(int i=0;i<5;i++)queue.addLast(Integer.toString(i));
        for(int millis=0;millis<5_000&&!queue.isEmpty();millis+=50)if(gate.take(ms(millis),0)){
            int tile=Integer.parseInt(queue.removeFirst());
            if(++hops[tile]<5)queue.addLast(Integer.toString(tile));
        }
        for(int hop:hops)check(hop==5,"five publications with five authorized hops each all progress without the old four-poster burst assumption");
        check(PosterRequestAdmission.MAX_PENDING==32&&PosterRequestAdmission.MAX_IN_FLIGHT==16,
                "local queue is bounded independently from unchanged maximum requests on the wire");
        System.out.println("PASS: "+checks+" poster authorization pacing and bounded admission checks.");
    }
}
