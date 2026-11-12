package local.cinemamanager.operations;

/** UI acknowledgement state: refreshes cannot complete mutations; timed-out commands are never replayed. */
public final class OperationsSession {
    private String pending="",refresh="";
    private int sentAt,lastReply;
    private boolean fresh=true;
    public boolean pending(){return !pending.isEmpty();}
    public boolean fresh(int tick){return fresh&&tick-lastReply<=200;}
    public boolean begin(String request,int tick,boolean command){
        if(request==null||request.isBlank()||pending()||command&&!fresh(tick)||!command&&!refresh.isEmpty())return false;
        if(command){pending=request;refresh="";}else refresh=request;
        sentAt=tick;return true;
    }
    public boolean accept(String request,int tick){
        if(request==null||request.isBlank()||!request.equals(pending)&&!request.equals(refresh))return false;
        if(request.equals(pending))pending="";if(request.equals(refresh))refresh="";
        lastReply=tick;fresh=true;return true;
    }
    public boolean tick(int tick){
        if((!pending.isEmpty()||!refresh.isEmpty())&&tick-sentAt>100){boolean command=pending();pending="";refresh="";fresh=false;return command;}
        return false;
    }
    public void close(){pending="";refresh="";fresh=false;}
}
