package local.cinemamanager.switches;

import java.util.*;

/** Fixed-ID device operations. This is not a block placement API or an atomic world transaction. */
public final class RegisteredSwitchEngine {
    public enum Kind { LEVER, FLOOR_LEVER, IE_SIGNAL, UNIMPLEMENTED }
    public enum Status { REFUSED, PARTIAL, PENDING_VERIFY, CONFIRMED }
    public record Pos(int x, int y, int z) {}
    public record Device(String id, String group, Kind kind, String dimension, Pos controller,
            Pos breaker, String facing, String installation, boolean eligible, boolean routeCertified,
            String heldReason) {}
    public record Observation(boolean authenticated, boolean powered, boolean controllerMode,
            boolean breakerMode, int externalControllerWires, Boolean breakerInternal,
            Boolean breakerActive, boolean downstreamReady, String fault) {}
    public record Result(Status status, String message, List<String> changed, List<String> pending) {
        public Result { changed = List.copyOf(changed); pending = List.copyOf(pending); }
        public boolean confirmed() { return status == Status.CONFIRMED; }
    }
    public interface Gateway {
        /** Read-only. Must check exact world profile and installation/registry receipt. */
        String worldFault();
        long tick();
        Observation observe(Device d);
        default Observation observe(Device d,boolean on){return observe(d);}
        /** Return false if native transition fails. Must revalidate immediately before the transition. */
        boolean request(Device d, boolean on);
    }
    private record Pending(boolean on, long tick, List<String> ids, List<String> changed) {}
    private final Map<String,Device> devices;
    private final List<Pending> pending = new ArrayList<>();
    public RegisteredSwitchEngine(Collection<Device> definitions) {
        LinkedHashMap<String,Device> map = new LinkedHashMap<>();
        for (Device d : definitions) {
            if (d == null || d.id() == null || !d.id().matches("[a-z][a-z0-9_]{0,63}") ||
                    !"minecraft:overworld".equals(d.dimension()) || map.putIfAbsent(d.id(),d) != null)
                throw new IllegalArgumentException("Malformed or duplicate fixed registry");
            if (d.eligible() && (d.controller() == null || d.installation() == null || d.installation().isBlank()
                    || (d.kind()==Kind.IE_SIGNAL && d.breaker()==null) || d.kind()==Kind.UNIMPLEMENTED))
                throw new IllegalArgumentException("Incomplete enrolled registration");
        }
        devices = Collections.unmodifiableMap(map);
    }
    public synchronized boolean hasPending(){return !pending.isEmpty();}
    /** Optional read-only settlement for ordinary status; absence is not an error or certification. */
    public synchronized Result readStatus(Gateway gateway){return pending.isEmpty()?null:verify(gateway);}
    private Result refused(String text) { return new Result(Status.REFUSED,text,List.of(),List.of()); }
    private String validate(Device d, Observation o, boolean on) {
        if (o == null || !o.authenticated()) return "identity/type/chunk refusal: "+d.id();
        if (o.fault()!=null && !o.fault().isBlank()) return d.id()+": "+o.fault();
        if(d.kind()==Kind.FLOOR_LEVER && on && (!d.routeCertified()||!o.downstreamReady()))return "FLOOR_ON_ROUTE_UNVERIFIED: "+d.id();
        if (d.kind()==Kind.IE_SIGNAL) {
            if (o.controllerMode() || !o.breakerMode()) return "MODE_MISMATCH: "+d.id()+"; configuration was not changed";
            if (o.externalControllerWires()!=0) return "controller has external power wiring: "+d.id();
            // OFF intentionally ignores route completeness and every downstream lamp.
            if (on && (!d.routeCertified() || !o.downstreamReady())) return "ON topology/downstream unverified: "+d.id();
        }
        return null;
    }
    public synchronized Result apply(List<String> ids, boolean on, Gateway gateway) {
        List<String> changed=new ArrayList<>(),waits=new ArrayList<>();
        // Normal movie->pause buttons must not require a hidden manual verify command.
        // Only a new ON may retire already-settled prior records through read-only verification.
        // Same-tick/unresolved records remain pending; OFF never pre-reads unrelated/downstream work.
        if(on&&!pending.isEmpty())verify(gateway);
        try{return applyChecked(ids,on,gateway,changed,waits);}
        catch(RuntimeException|LinkageError bad){return stop("Callback failed: "+bad.getClass().getSimpleName()+"; inspect physical state",changed,waits);}
    }
    private Result applyChecked(List<String> ids,boolean on,Gateway gateway,List<String> changed,List<String> waits){
        if(ids==null || ids.isEmpty() || new HashSet<>(ids).size()!=ids.size()) return refused("Empty/duplicate target set");
        if(on && pending.stream().anyMatch(p->p.ids().stream().anyMatch(ids::contains)))
            return refused("An overlapping signal request awaits verification; explicit authenticated OFF may supersede it");
        String world = gateway.worldFault();
        if (world!=null) return refused(world);
        if (ids==null || ids.isEmpty() || new HashSet<>(ids).size()!=ids.size()) return refused("Empty/duplicate target set");
        List<Device> targets = new ArrayList<>();
        // Preflight every member before any write; no partial scene just because a later member is held.
        for (String id : ids) {
            Device d = devices.get(id);
            if (d==null) return refused("Unknown registered ID: "+id);
            if (!d.eligible()) return refused("UNREGISTERED: "+id+"; "+d.heldReason());
            String fault = validate(d,gateway.observe(d,on),on);
            if (fault!=null) return refused(fault);
            targets.add(d);
        }
        // An explicitly requested OFF supersedes only its overlapping pending targets, after OFF preflight.
        // It never has to make an older ON/downstream validation pass first. Unrelated requests remain tracked.
        if(!on){
            List<Pending> keep=new ArrayList<>();
            for(Pending p:pending){
                List<String> remaining=p.ids().stream().filter(id->!ids.contains(id)).toList();
                if(!remaining.isEmpty())keep.add(new Pending(p.on(),p.tick(),remaining,p.changed().stream().filter(remaining::contains).toList()));
            }
            pending.clear();pending.addAll(keep);
        }
        for (Device d : targets) {
            world = gateway.worldFault();
            Observation before = gateway.observe(d,on);
            String fault = world==null ? validate(d,before,on) : world;
            if (fault!=null) return stop(fault,changed,waits);
            if (before.powered()!=on) {
                // A request can have thrown after native neighbors changed. Include it conservatively.
                changed.add(d.id());
                try {
                    if (!gateway.request(d,on)) return stop("Native request failed: "+d.id(),changed,waits);
                } catch (RuntimeException ex) { return stop("Native request threw: "+d.id()+"; inspect physical state",changed,waits); }
            }
            Observation after = gateway.observe(d,on);
            fault=validate(d,after,on);
            if (fault!=null || after.powered()!=on) return stop(fault==null?"Controller readback failed: "+d.id():fault,changed,waits);
            if (d.kind()==Kind.IE_SIGNAL) waits.add(d.id());
        }
        // Native neighbor callbacks from later targets can change/invalidate earlier targets.
        // Re-read the entire target set before reporting group success or accepting verification work.
        world=gateway.worldFault();
        if(world!=null)return stop(world,changed,waits);
        for(Device d:targets){
            Observation finalState=gateway.observe(d,on);String fault=validate(d,finalState,on);
            if(fault!=null||finalState.powered()!=on)return stop(fault==null?"Final group readback changed: "+d.id():fault,changed,waits);
        }
        if (!waits.isEmpty()) {
            pending.add(new Pending(on,gateway.tick(),List.copyOf(ids),List.copyOf(changed)));
            return new Result(Status.PENDING_VERIFY,"Signal requested; electrical state is not yet confirmed",changed,waits);
        }
        return new Result(Status.CONFIRMED,"Existing controller states confirmed; neighbor/lamp state effects are real writes",changed,List.of());
    }
    private Result stop(String fault,List<String>changed,List<String>waits) {
        return new Result(changed.isEmpty()?Status.REFUSED:Status.PARTIAL,
                fault+"; no automatic rollback or repair",changed,waits);
    }
    /** Read-only after a native server tick; no schedules, forced ticks, reconfiguration or repair. */
    public synchronized Result verify(Gateway gateway) {
        try{return verifyChecked(gateway);}
        catch(RuntimeException|LinkageError bad){
            return new Result(Status.PARTIAL,"Verification callback failed: "+bad.getClass().getSimpleName()+"; no state certified",
                    pending.stream().flatMap(p->p.changed().stream()).distinct().toList(),pending.stream().flatMap(p->p.ids().stream()).distinct().toList());
        }
    }
    private Result verifyChecked(Gateway gateway){
        if(pending.isEmpty())return refused("No pending request; this does not certify prior or unknown physical state");
        List<String> allIds=pending.stream().flatMap(p->p.ids().stream()).distinct().toList();
        List<String> changed=pending.stream().flatMap(p->p.changed().stream()).distinct().toList();
        String world=gateway.worldFault();
        if(world!=null)return new Result(Status.PARTIAL,world,changed,allIds);
        List<Pending> remaining=new ArrayList<>();List<String> confirmed=new ArrayList<>(),faults=new ArrayList<>();boolean faulted=false;
        for(Pending p:pending){
            String issue=null;boolean hardFault=false;
            try{
                if(gateway.tick()<=p.tick())issue="Await a later native tick: "+p.ids();
                else for(String id:p.ids()){
                    Device d=devices.get(id);Observation o=gateway.observe(d,p.on());String fault=validate(d,o,p.on());
                    if(fault!=null||o.powered()!=p.on()){issue=fault==null?"Controller changed: "+id:fault;hardFault=true;break;}
                    if(d.kind()==Kind.IE_SIGNAL){
                        if(o.breakerInternal()==null||o.breakerActive()==null){issue="Breaker network/state unknown: "+id;break;}
                        // IE 12.4.2-194: active=!redstone; passing=active XOR inverted. Mode is validated true.
                        if(o.breakerActive()==p.on()||o.breakerInternal()!=p.on()){issue="Breaker state pending: "+id;break;}
                    }
                }
            }catch(RuntimeException|LinkageError bad){issue="Read callback failed: "+p.ids();hardFault=true;}
            if(issue==null)confirmed.addAll(p.ids());
            else{remaining.add(p);faults.add(issue);faulted|=hardFault;}
        }
        pending.clear();pending.addAll(remaining);
        if(remaining.isEmpty())return new Result(Status.CONFIRMED,"Confirmed: "+confirmed+"; OFF did not require downstream lamps; ON does not certify brightness",changed,List.of());
        List<String> unresolved=remaining.stream().flatMap(p->p.ids().stream()).distinct().toList();
        return new Result(faulted||!confirmed.isEmpty()?Status.PARTIAL:Status.PENDING_VERIFY,
                "Individually confirmed: "+confirmed+"; unresolved: "+faults,changed,unresolved);
    }
}
