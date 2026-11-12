package local.cinemamanager.control;

/** Client interaction model; refreshes never move a held thumb or change its server lease. */
public final class SeekDrag {
    public record View(String hall,String session,String ticket,long duration,long position,boolean allowed) {}
    public record Commit(String hall,String ticket,long target) {}
    private View current, frozen;
    private long preview;
    private boolean dragging, pending;
    private String pendingTicket="";
    public void update(View next) {
        current=next;
        if(dragging && (!compatible(frozen,next))) cancel();
        if(!dragging && !pending) preview=next==null?0:Math.clamp(next.position,0,Math.max(0,next.duration));
    }
    public boolean begin() {
        if(current==null || !current.allowed || current.ticket==null || current.ticket.isBlank() || pending)return false;
        frozen=current;preview=Math.clamp(current.position,0,current.duration);dragging=true;return true;
    }
    public void move(long value){if(dragging)preview=Math.clamp(value,0,frozen.duration);}
    public Commit release() {
        if(!dragging)return null;
        dragging=false;
        if(!compatible(frozen,current)){cancel();return null;}
        if(preview==frozen.position){frozen=null;return null;}
        Commit result=new Commit(frozen.hall,frozen.ticket,preview);pendingTicket=frozen.ticket;frozen=null;pending=true;return result;
    }
    public boolean acknowledge(String ticket){if(!pending||!pendingTicket.equals(ticket))return false;reply();return true;}
    private void reply(){pending=false;pendingTicket="";if(!dragging&&current!=null)preview=Math.clamp(current.position,0,Math.max(0,current.duration));}
    public void cancel(){dragging=false;pending=false;pendingTicket="";frozen=null;preview=current==null?0:Math.max(0,current.position);}
    public boolean dragging(){return dragging;}
    public boolean pending(){return pending;}
    public long preview(){return preview;}
    public long duration(){return dragging?frozen.duration:current==null?0:current.duration;}
    private static boolean compatible(View a,View b){return a!=null&&b!=null&&b.allowed&&a.hall.equals(b.hall)&&a.session.equals(b.session)&&a.duration==b.duration;}
}
