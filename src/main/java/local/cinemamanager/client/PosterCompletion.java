package local.cinemamanager.client;

/** One saved-source load owns one slot. Retry/source changes create another slot. */
final class PosterCompletion<T> {
    record Outcome<T>(T value,PosterFailure failure){}
    private boolean cancelled,completed,taken;
    private Outcome<T> outcome;
    synchronized boolean complete(T value,PosterFailure failure){
        if(cancelled||completed)return false;
        if((value==null)==(failure==null))throw new IllegalArgumentException("One success or one failure is required");
        completed=true;outcome=new Outcome<>(value,failure);return true;
    }
    synchronized Outcome<T> take(){if(cancelled||!completed||taken)return null;taken=true;Outcome<T> result=outcome;outcome=null;return result;}
    synchronized void cancel(){cancelled=true;outcome=null;}
}
