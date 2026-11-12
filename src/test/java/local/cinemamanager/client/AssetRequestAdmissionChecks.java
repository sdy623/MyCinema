package local.cinemamanager.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Replays the observed five-request burst against production start pacing, without threads or network. */
public final class AssetRequestAdmissionChecks {
    private static int checks;
    private static void check(boolean yes,String reason){checks++;if(!yes)throw new AssertionError(reason);}
    private static long ms(long millis){return TimeUnit.MILLISECONDS.toNanos(millis);}

    public static void main(String[] args){
        var gate=new AssetRequestAdmission();
        check(gate.take(ms(0)),"first actual private-asset request can start immediately");
        // Native run: starts at .246, .347, .413, .514, .614; the second failed after metadata.
        for(long time:new long[]{101,167,268,368})check(!gate.take(ms(time)),
                "the observed native burst waits locally instead of silently exceeding the server start window");
        check(!gate.take(ms(499))&&gate.take(ms(500)),"waiting requests become eligible at the pacing boundary");
        check(!gate.take(ms(500)),"a metadata error or cancellation cannot refund an already sent request");

        gate=new AssetRequestAdmission();
        List<Long> starts=new ArrayList<>();
        int request=0;long previousCompleted=0;
        for(long time=0;time<15_000&&request<5;time+=25){
            if(time<previousCompleted||!gate.take(ms(time)))continue;
            starts.add(time);
            // Both success and the real second-request memory rejection consume one admission.
            previousCompleted=time+(request==1?65:95);
            request++;
        }
        check(starts.equals(List.of(0L,500L,1_000L,1_500L,2_000L)),
                "five independent reads all start in two seconds even when metadata rejects the second read");
        int windowCount=0;long windowStart=0;
        for(long time:starts){
            long serverTick=time/50;
            if(serverTick-windowStart>=20){windowStart=serverTick;windowCount=0;}
            check(++windowCount<=4,"native-rate server model retains the unchanged four-starts-per-20-ticks bound");
        }

        gate=new AssetRequestAdmission();
        check(gate.take(ms(0)),"an upload Begin consumes the same start allowance");
        check(!gate.take(ms(100)),"a poster Download cannot bypass pacing immediately after upload Begin");
        check(gate.take(ms(500)),"the waiting poster can start after the shared interval");
        for(int frame=0;frame<100;frame++)check(!gate.take(ms(501)),"repeated rendering does not create a retry burst");
        check(!gate.take(ms(100)),"clock regression does not permit a request burst");
        check(gate.take(ms(30_000))&&!gate.take(ms(30_000)),"idle time cannot accumulate multiple immediate starts");
        check(AssetRequestAdmission.SPACING_NANOS==ms(500),"conservative pacing leaves room below the unchanged server window");
        System.out.println("PASS: "+checks+" private-asset start admission checks.");
    }
}
