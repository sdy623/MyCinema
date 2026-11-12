package local.cinemamanager.access;

import java.util.*;

/** Persistent, cinema-scoped override model. It never grants media or ticket authority. */
public final class EmergencyAccessBook {
    public static final int MAX_CINEMAS = 4096, MAX_AUDIT = 256;
    public record Scope(UUID controller, UUID owner, long pos) {
        public Scope { Objects.requireNonNull(controller); Objects.requireNonNull(owner); }
    }
    public record State(boolean active, long revision, long changedAt, boolean retired) {
        public State(boolean active,long revision,long changedAt){this(active,revision,changedAt,false);}
        public State { if(revision<0 || changedAt<0 || active&&retired)throw new IllegalArgumentException("mycinema.emergency.invalid"); }
    }
    public enum Reason { USER, CONTROLLER_INVALID }
    public record Audit(Scope scope, UUID actor, boolean active, long revision, long changedAt, long gameTime, Reason reason) {
        public Audit(Scope scope,UUID actor,boolean active,long revision,long changedAt,long gameTime){this(scope,actor,active,revision,changedAt,gameTime,Reason.USER);}
        public Audit { Objects.requireNonNull(scope);Objects.requireNonNull(reason);if(reason==Reason.USER)Objects.requireNonNull(actor);if(revision<1||changedAt<0||gameTime<0)throw new IllegalArgumentException("mycinema.emergency.invalid"); }
    }
    private static final State NORMAL = new State(false,0,0);
    private final Map<Scope,State> states = new LinkedHashMap<>();
    private final Deque<Audit> audit = new ArrayDeque<>();
    public State state(Scope scope) { return scope==null?NORMAL:states.getOrDefault(scope,NORMAL); }
    public Map<Scope,State> states() { return Map.copyOf(states); }
    public List<Audit> audit() { return List.copyOf(audit); }
    /** Explicit desired state and compare-and-set revision make replays and repeated clicks safe. */
    public boolean change(Scope scope,UUID actor,boolean active,long expected,long now,long gameTime) {
        Objects.requireNonNull(scope);Objects.requireNonNull(actor);
        State previous=state(scope);
        if(previous.retired())throw new IllegalArgumentException("mycinema.emergency.retired");
        if(expected!=previous.revision())throw new IllegalArgumentException("mycinema.emergency.stale");
        if(previous.active()==active)return false;
        if(!states.containsKey(scope)&&states.size()>=MAX_CINEMAS)throw new IllegalArgumentException("mycinema.emergency.limit");
        long next=Math.addExact(previous.revision(),1);
        State changed=new State(active,next,now);
        Audit action=new Audit(scope,actor,active,next,now,gameTime);
        states.put(scope,changed);audit.addLast(action);
        while(audit.size()>MAX_AUDIT)audit.removeFirst();
        return true;
    }
    /** Once an invalid loaded controller is observed, unloading it can never revive that identity. */
    public boolean retire(Scope scope,long now,long gameTime) {
        State previous=state(scope);
        if(!previous.active()||previous.retired())return false;
        long next=Math.addExact(previous.revision(),1);
        states.put(scope,new State(false,next,now,true));
        audit.addLast(new Audit(scope,null,false,next,now,gameTime,Reason.CONTROLLER_INVALID));
        while(audit.size()>MAX_AUDIT)audit.removeFirst();
        return true;
    }
    /** Loading duplicate identities fails closed rather than choosing the last supplied override. */
    public void restore(Collection<Map.Entry<Scope,State>> saved,Collection<Audit> actions) {
        states.clear();audit.clear();Set<Scope> conflicts=new HashSet<>();
        for(var entry:saved){
            if(entry==null||entry.getKey()==null||entry.getValue()==null)continue;
            Scope scope=entry.getKey();
            if(conflicts.contains(scope))continue;
            if(states.containsKey(scope)){states.remove(scope);conflicts.add(scope);continue;}
            if(states.size()<MAX_CINEMAS)states.put(scope,entry.getValue());
        }
        for(Audit action:actions)if(action!=null){audit.addLast(action);while(audit.size()>MAX_AUDIT)audit.removeFirst();}
    }
}
