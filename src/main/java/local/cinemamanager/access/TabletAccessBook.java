package local.cinemamanager.access;

import java.util.*;

/** Server-only registrations created by the existing tablet's local terminal activation. */
public final class TabletAccessBook {
    public static final int MAX_GRANTS=4096;
    public static final int CURRENT_VERSION=1;
    public record Scope(UUID controller,UUID owner,long pos) {
        public Scope { Objects.requireNonNull(controller);Objects.requireNonNull(owner); }
    }
    public record Grant(UUID token,UUID player,Scope scope) {
        public Grant { Objects.requireNonNull(token);Objects.requireNonNull(player);Objects.requireNonNull(scope); }
    }
    private final Map<UUID,Grant> grants=new LinkedHashMap<>();
    public Grant activate(UUID player,Scope scope) { return activate(player,scope,null); }
    /** Check capacity before mutation, crediting an owned previous token in this same store. */
    public Grant activate(UUID player,Scope scope,UUID previousToken) {
        Objects.requireNonNull(player);Objects.requireNonNull(scope);
        java.util.function.Predicate<Grant> replaced=g->g.player().equals(player)
                &&(g.scope().controller().equals(scope.controller())||g.token().equals(previousToken));
        long replacing=grants.values().stream().filter(replaced).count();
        if(grants.size()-replacing>=MAX_GRANTS)throw new IllegalArgumentException("mycinema.access.limit");
        grants.values().removeIf(replaced);
        UUID token;do{token=UUID.randomUUID();}while(grants.containsKey(token));
        Grant grant=new Grant(token,player,scope);grants.put(token,grant);return grant;
    }
    public boolean registered(UUID token,UUID player) { Grant grant=grants.get(token);return grant!=null&&grant.player().equals(player); }
    public boolean authorize(UUID token,UUID player,Scope scope) { return authorize(token,player,scope,CURRENT_VERSION); }
    public boolean authorize(UUID token,UUID player,Scope scope,int version) { Grant grant=grants.get(token);return version==CURRENT_VERSION&&grant!=null&&grant.player().equals(player)&&grant.scope().equals(scope); }
    public boolean revoke(UUID token,UUID player) { return registered(token,player)&&grants.remove(token)!=null; }
    public Collection<Grant> all(){return List.copyOf(grants.values());}
    public void restore(Collection<Grant> saved) {
        grants.clear();Set<UUID> duplicates=new HashSet<>();
        for(Grant grant:saved){
            if(grant==null||duplicates.contains(grant.token()))continue;
            if(grants.containsKey(grant.token())){grants.remove(grant.token());duplicates.add(grant.token());continue;}
            if(grants.size()<MAX_GRANTS)grants.put(grant.token(),grant);
        }
    }
}
