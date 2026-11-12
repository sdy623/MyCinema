package local.cinemamanager.operations;

import local.cinemamanager.panels.PanelScope;
import java.util.*;

/**
 * Pure policy over facts captured by a future server adapter, never packet-supplied authority.
 * Does not issue grants, persist data, open panels, control playback or replace local-media consent.
 */
public final class OperationsPolicy {
    public enum Capability { OPERATIONS_VIEW, PLAYBACK_CONTROL }
    public enum Action { VIEW, PLAY, PAUSE, STOP, SEEK_CURRENT, PREVIOUS, NEXT, CHOOSE }
    public enum Denial { NONE, INVALID_CONTEXT, DISCONNECTED, SPECTATOR, UNAVAILABLE, WRONG_DIMENSION,
        TOO_FAR, BLOCK_INTERACTION_DENIED, NOT_OPERATIONS, STALE_PANEL, STALE_BINDING, NO_OWNER,
        WRONG_HALL, NO_GRANT, GRANT_REVOKED, GRANT_EXPIRED, STALE_AUTHORITY, CAPABILITY_MISSING,
        NO_PROGRAMME, STALE_PROGRAMME }
    public record Actor(UUID id,String dimension,boolean genuineConnectedPlayer,boolean spectator,
                        boolean mayInteract,double panelDistanceSquared) {
        public Actor {Objects.requireNonNull(id);dimension=OperationsPolicy.dimension(dimension);if(!Double.isFinite(panelDistanceSquared)||panelDistanceSquared<0)throw invalid();}
    }
    public record Controller(UUID id,UUID owner,String dimension,long authorityEpoch,boolean loaded,Set<UUID> halls) {
        public Controller {Objects.requireNonNull(id);dimension=OperationsPolicy.dimension(dimension);if(authorityEpoch<0)throw invalid();halls=bounded(halls,128);}
    }
    public record Panel(UUID id,UUID owner,String block,String dimension,UUID controller,UUID hall,
                        long revision,boolean loaded,PanelScope scope,TerminalRoleSettings settings) {
        public Panel {Objects.requireNonNull(id);dimension=OperationsPolicy.dimension(dimension);if(revision<0||block==null||settings==null||scope==null)throw invalid();}
    }
    /** This record belongs to private server storage. It is not an item, packet or bearer credential. */
    public record Grant(UUID id,UUID controller,UUID issuedBy,UUID holder,long authorityEpoch,Set<UUID> halls,
                        Set<Capability> capabilities,long expiresAtUtc,boolean revoked) {
        public Grant {Objects.requireNonNull(id);Objects.requireNonNull(controller);Objects.requireNonNull(issuedBy);Objects.requireNonNull(holder);
            if(authorityEpoch<1||expiresAtUtc<0)throw invalid();halls=bounded(halls,128);capabilities=bounded(capabilities,Capability.values().length);if(halls.isEmpty()||capabilities.isEmpty())throw invalid();}
    }
    public record Expected(UUID panel,long panelRevision,UUID controller,UUID hall,UUID run,long programmeRevision,int clipIndex) {
        public Expected {Objects.requireNonNull(panel);Objects.requireNonNull(controller);if(panelRevision<0||programmeRevision<0||clipIndex<0)throw invalid();}
    }
    public record Programme(UUID run,long revision,int clipIndex) {
        public Programme {Objects.requireNonNull(run);if(revision<0||clipIndex<0)throw invalid();}
    }
    public static final class Decision {
        private final Denial denial;
        private final Action action;
        private final Expected expected;
        private Decision(Denial denial,Action action,Expected expected){this.denial=denial;this.action=action;this.expected=expected;}
        public boolean allowed(){return denial==Denial.NONE;}
        public Denial denial(){return denial;}
        /** Consumers must bind the decision to the exact request; never reuse a VIEW decision to control. */
        public boolean permits(Action action,Expected target){return allowed()&&this.action==action&&Objects.equals(expected,target);}
    }
    public static Decision authorize(Actor actor,Controller controller,Panel panel,Grant grant,Action action,
                                     Expected expected,Programme programme,long serverUtc){
        Denial denial=evaluate(actor,controller,panel,grant,action,expected,programme,serverUtc);
        return new Decision(denial,action,expected);
    }
    private static Denial evaluate(Actor actor,Controller controller,Panel panel,Grant grant,Action action,
                                   Expected expected,Programme programme,long now){
        if(actor==null||controller==null||panel==null||action==null||expected==null||now<0)return Denial.INVALID_CONTEXT;
        if(!actor.genuineConnectedPlayer())return Denial.DISCONNECTED;
        if(actor.spectator())return Denial.SPECTATOR;
        if(!controller.loaded()||!panel.loaded())return Denial.UNAVAILABLE;
        if(!actor.dimension().equals(controller.dimension())||!actor.dimension().equals(panel.dimension()))return Denial.WRONG_DIMENSION;
        if(actor.panelDistanceSquared()>64)return Denial.TOO_FAR;
        if(!actor.mayInteract())return Denial.BLOCK_INTERACTION_DENIED;
        if(panel.settings().role()!=TerminalRoleSettings.Role.OPERATIONS||!panel.settings().validFor(panel.block(),panel.scope()))return Denial.NOT_OPERATIONS;
        if(!panel.id().equals(expected.panel())||panel.revision()!=expected.panelRevision())return Denial.STALE_PANEL;
        if(controller.owner()==null)return Denial.NO_OWNER;
        if(!controller.id().equals(panel.controller())||!controller.id().equals(expected.controller())||!controller.owner().equals(panel.owner()))return Denial.STALE_BINDING;
        if(panel.scope()==PanelScope.HALL&&(panel.hall()==null||!panel.hall().equals(expected.hall())))return Denial.WRONG_HALL;
        if(expected.hall()!=null&&!controller.halls().contains(expected.hall())||action!=Action.VIEW&&expected.hall()==null)return Denial.WRONG_HALL;
        if(!actor.id().equals(controller.owner())){
            if(grant==null||!grant.holder().equals(actor.id()))return Denial.NO_GRANT;
            if(grant.revoked())return Denial.GRANT_REVOKED;
            if(!grant.controller().equals(controller.id())||!grant.issuedBy().equals(controller.owner())||grant.authorityEpoch()!=controller.authorityEpoch())return Denial.STALE_AUTHORITY;
            if(grant.expiresAtUtc()!=0&&now>=grant.expiresAtUtc())return Denial.GRANT_EXPIRED;
            if(expected.hall()==null?!grant.halls().containsAll(controller.halls()):!grant.halls().contains(expected.hall()))return Denial.WRONG_HALL;
            if(!grant.capabilities().contains(Capability.OPERATIONS_VIEW)||action!=Action.VIEW&&!grant.capabilities().contains(Capability.PLAYBACK_CONTROL))return Denial.CAPABILITY_MISSING;
        }
        if(action!=Action.VIEW){
            if(programme==null)return Denial.NO_PROGRAMME;
            if(expected.run()==null||!programme.run().equals(expected.run())||programme.revision()!=expected.programmeRevision()||programme.clipIndex()!=expected.clipIndex())return Denial.STALE_PROGRAMME;
        }
        return Denial.NONE;
    }
    private static String dimension(String value){if(value==null||value.length()>128||!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw invalid();return value;}
    private static <T> Set<T> bounded(Set<T> input,int max){if(input==null||input.size()>max||input.stream().anyMatch(Objects::isNull))throw invalid();return Set.copyOf(input);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("mycinema.operations.invalid_context");}
    private OperationsPolicy(){}
}
