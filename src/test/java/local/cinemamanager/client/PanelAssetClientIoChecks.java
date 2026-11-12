package local.cinemamanager.client;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises the real bounded file executor without opening a screen or a network connection. */
public final class PanelAssetClientIoChecks {
    public static void main(String[] args)throws Exception{
        CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1),hold=new CountDownLatch(1);
        CompletableFuture<Void> running=PanelAssetClient.async(()->{
            entered.countDown();try{hold.await();}catch(InterruptedException expected){interrupted.countDown();Thread.currentThread().interrupt();throw new IOException("asset_cancelled");}return null;
        });
        if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("file worker did not start");
        var queuedRan=new AtomicBoolean();CompletableFuture<Void> queued=PanelAssetClient.async(()->{queuedRan.set(true);return null;});
        try{
            if(!queued.cancel(true)||!running.cancel(true))throw new AssertionError("cancellation was not accepted");
            if(!interrupted.await(2,TimeUnit.SECONDS))throw new AssertionError("cancelling a running result did not interrupt its executor Future");
            if(PanelAssetClient.async(()->37).get(2,TimeUnit.SECONDS)!=37)throw new AssertionError("file executor did not recover after cancellation");
            if(queuedRan.get())throw new AssertionError("cancelled queued task still ran");
        }finally{hold.countDown();running.cancel(true);queued.cancel(true);}
        System.out.println("Private asset client cancellation checks passed");
    }
}
