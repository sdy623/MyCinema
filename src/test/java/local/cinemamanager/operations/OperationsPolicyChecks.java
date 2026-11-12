package local.cinemamanager.operations;

import local.cinemamanager.panels.PanelScope;
import java.util.*;
import static local.cinemamanager.operations.OperationsPolicy.*;

/** Pure source tests; this independent branch has not run a JVM yet. */
public final class OperationsPolicyChecks {
    private static final UUID OWNER=new UUID(1,1),STAFF=new UUID(2,2),OTHER=new UUID(3,3),CONTROL=new UUID(4,4),PANEL=new UUID(5,5),HALL=new UUID(6,6),RUN=new UUID(7,7),GRANT=new UUID(8,8);
    private static final String DIM="minecraft:overworld";
    private static final TerminalRoleSettings OPS=new TerminalRoleSettings(1,TerminalRoleSettings.Role.OPERATIONS,TerminalRoleSettings.View.OPERATIONS);
    private static Actor actor(UUID id){return new Actor(id,DIM,true,false,true,64);}
    private static Controller controller(){return new Controller(CONTROL,OWNER,DIM,2,true,Set.of(HALL));}
    private static Panel panel(){return new Panel(PANEL,OWNER,"mycinema:information_panel",DIM,CONTROL,HALL,3,true,PanelScope.HALL,OPS);}
    private static Expected expected(){return new Expected(PANEL,3,CONTROL,HALL,RUN,4,1);}
    private static Programme programme(){return new Programme(RUN,4,1);}
    private static Grant grant(){return new Grant(GRANT,CONTROL,OWNER,STAFF,2,Set.of(HALL),Set.of(Capability.OPERATIONS_VIEW,Capability.PLAYBACK_CONTROL),200,false);}
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void deny(Denial reason,Actor actor,Controller controller,Panel panel,Grant grant,Action action,Expected expected,Programme programme,long now){
        Decision decision=authorize(actor,controller,panel,grant,action,expected,programme,now);check(!decision.allowed()&&decision.denial()==reason,"expected refusal "+reason+", got "+decision.denial());check(!decision.permits(action,expected),"refused decision never permits action");
    }
    private static void malformed(Runnable run){try{run.run();throw new AssertionError("accepted malformed value");}catch(IllegalArgumentException|NullPointerException expected){checks++;}}
    public static void main(String[] args){
        check(TerminalRoleSettings.decode(null).equals(TerminalRoleSettings.LEGACY),"absent old-world role is audience programme");
        for(TerminalRoleSettings.View view:TerminalRoleSettings.View.values()){
            TerminalRoleSettings settings=new TerminalRoleSettings(1,view==TerminalRoleSettings.View.OPERATIONS?TerminalRoleSettings.Role.OPERATIONS:TerminalRoleSettings.Role.AUDIENCE,view);
            check(TerminalRoleSettings.decode(settings.encode()).equals(settings),"strict role/view roundtrip");
        }
        for(String raw:List.of("", "{}", "null", "[]", "{broken}","{\"schema\":\"1\",\"role\":\"OPERATIONS\",\"view\":\"OPERATIONS\"}","{\"schema\":1.5,\"role\":\"OPERATIONS\",\"view\":\"OPERATIONS\"}","{\"schema\":2,\"role\":\"OPERATIONS\",\"view\":\"OPERATIONS\"}","{\"schema\":1,\"role\":true,\"view\":\"OPERATIONS\"}","{\"schema\":1,\"role\":\"AUDIENCE\",\"view\":\"OPERATIONS\"}","{\"schema\":1,\"role\":\"OPERATIONS\",\"view\":\"PROGRAMME\"}","{\"schema\":1,\"role\":\"OWNER\",\"view\":\"OPERATIONS\"}","{\"schema\":1,\"role\":\"AUDIENCE\",\"view\":\"PROGRAMME\",\"allowed\":true}"))malformed(()->TerminalRoleSettings.decode(raw));
        check(OPS.validFor("mycinema:information_panel",PanelScope.HALL)&&OPS.validFor("mycinema:wall_information_panel",PanelScope.HALL),"existing information IDs support operations role");
        check(!OPS.validFor("mycinema:advertising_led",PanelScope.HALL)&&!OPS.validFor("mycinema:information_panel",PanelScope.MANUAL),"shared tile class does not grant new block/scope authority");
        TerminalRoleSettings today=new TerminalRoleSettings(1,TerminalRoleSettings.Role.AUDIENCE,TerminalRoleSettings.View.DAY_SCHEDULE);
        check(!today.validFor("mycinema:information_panel",PanelScope.MANUAL)&&today.validFor("mycinema:information_panel",PanelScope.LOBBY),"today's schedule needs a real hall/lobby binding");
        check(!OPS.validFor(null,PanelScope.HALL),"absent block cannot gain operations authority");
        for(Action action:Action.values()){
            Decision owner=authorize(actor(OWNER),controller(),panel(),null,action,expected(),programme(),100);check(owner.permits(action,expected()),"current owner can act on explicitly configured local console");
            Decision staff=authorize(actor(STAFF),controller(),panel(),grant(),action,expected(),programme(),199);check(staff.permits(action,expected()),"explicit current grant allows the scoped action");
        }
        Decision view=authorize(actor(STAFF),controller(),panel(),grant(),Action.VIEW,expected(),null,100);
        check(view.allowed()&&!view.permits(Action.STOP,expected()),"VIEW is not reusable for a control action");
        check(!view.permits(Action.VIEW,new Expected(UUID.randomUUID(),3,CONTROL,HALL,RUN,4,1)),"decision is bound to exact request target");
        deny(Denial.NO_GRANT,actor(STAFF),controller(),panel(),null,Action.PLAY,expected(),programme(),100);
        Grant readOnly=new Grant(GRANT,CONTROL,OWNER,STAFF,2,Set.of(HALL),Set.of(Capability.OPERATIONS_VIEW),0,false);
        check(authorize(actor(STAFF),controller(),panel(),readOnly,Action.VIEW,expected(),null,Long.MAX_VALUE).allowed(),"explicit non-expiring read-only grant still permits only view");
        deny(Denial.CAPABILITY_MISSING,actor(STAFF),controller(),panel(),readOnly,Action.PAUSE,expected(),programme(),100);
        Grant blindControl=new Grant(GRANT,CONTROL,OWNER,STAFF,2,Set.of(HALL),Set.of(Capability.PLAYBACK_CONTROL),0,false);
        deny(Denial.CAPABILITY_MISSING,actor(STAFF),controller(),panel(),blindControl,Action.PLAY,expected(),programme(),100);
        deny(Denial.GRANT_EXPIRED,actor(STAFF),controller(),panel(),grant(),Action.VIEW,expected(),null,200);
        deny(Denial.GRANT_REVOKED,actor(STAFF),controller(),panel(),new Grant(GRANT,CONTROL,OWNER,STAFF,2,Set.of(HALL),grant().capabilities(),0,true),Action.VIEW,expected(),null,100);
        deny(Denial.STALE_AUTHORITY,actor(STAFF),controller(),panel(),new Grant(GRANT,CONTROL,OWNER,STAFF,1,Set.of(HALL),grant().capabilities(),0,false),Action.PLAY,expected(),programme(),100);
        deny(Denial.STALE_AUTHORITY,actor(STAFF),controller(),panel(),new Grant(GRANT,OTHER,OWNER,STAFF,2,Set.of(HALL),grant().capabilities(),0,false),Action.PLAY,expected(),programme(),100);
        deny(Denial.STALE_AUTHORITY,actor(STAFF),controller(),panel(),new Grant(GRANT,CONTROL,OTHER,STAFF,2,Set.of(HALL),grant().capabilities(),0,false),Action.PLAY,expected(),programme(),100);
        deny(Denial.WRONG_HALL,actor(STAFF),controller(),panel(),new Grant(GRANT,CONTROL,OWNER,STAFF,2,Set.of(OTHER),grant().capabilities(),0,false),Action.VIEW,expected(),null,100);
        deny(Denial.DISCONNECTED,new Actor(OWNER,DIM,false,false,true,1),controller(),panel(),null,Action.VIEW,expected(),null,100);
        deny(Denial.SPECTATOR,new Actor(OWNER,DIM,true,true,true,1),controller(),panel(),null,Action.VIEW,expected(),null,100);
        deny(Denial.WRONG_DIMENSION,new Actor(OWNER,"minecraft:the_nether",true,false,true,1),controller(),panel(),null,Action.VIEW,expected(),null,100);
        deny(Denial.TOO_FAR,new Actor(OWNER,DIM,true,false,true,64.001),controller(),panel(),null,Action.VIEW,expected(),null,100);
        deny(Denial.BLOCK_INTERACTION_DENIED,new Actor(OWNER,DIM,true,false,false,1),controller(),panel(),null,Action.VIEW,expected(),null,100);
        deny(Denial.UNAVAILABLE,actor(OWNER),new Controller(CONTROL,OWNER,DIM,2,false,Set.of(HALL)),panel(),null,Action.VIEW,expected(),null,100);
        deny(Denial.NOT_OPERATIONS,actor(OWNER),controller(),new Panel(PANEL,OWNER,"mycinema:information_panel",DIM,CONTROL,HALL,3,true,PanelScope.HALL,TerminalRoleSettings.LEGACY),null,Action.VIEW,expected(),null,100);
        deny(Denial.STALE_PANEL,actor(OWNER),controller(),panel(),null,Action.VIEW,new Expected(PANEL,2,CONTROL,HALL,RUN,4,1),null,100);
        deny(Denial.STALE_BINDING,actor(OWNER),controller(),new Panel(PANEL,OTHER,"mycinema:information_panel",DIM,CONTROL,HALL,3,true,PanelScope.HALL,OPS),null,Action.VIEW,expected(),null,100);
        deny(Denial.NO_OWNER,actor(OWNER),new Controller(CONTROL,null,DIM,2,true,Set.of(HALL)),panel(),null,Action.VIEW,expected(),null,100);
        deny(Denial.NO_PROGRAMME,actor(OWNER),controller(),panel(),null,Action.STOP,expected(),null,100);
        deny(Denial.STALE_PROGRAMME,actor(OWNER),controller(),panel(),null,Action.SEEK_CURRENT,expected(),new Programme(RUN,5,1),100);
        deny(Denial.STALE_PROGRAMME,actor(OWNER),controller(),panel(),null,Action.CHOOSE,expected(),new Programme(RUN,4,2),100);
        deny(Denial.STALE_PROGRAMME,actor(OWNER),controller(),panel(),null,Action.PLAY,expected(),new Programme(OTHER,4,1),100);
        deny(Denial.INVALID_CONTEXT,actor(OWNER),controller(),panel(),null,Action.VIEW,expected(),null,-1);
        malformed(()->new Actor(OWNER,DIM,true,false,true,Double.NaN));malformed(()->new Actor(OWNER,DIM,true,false,true,Double.POSITIVE_INFINITY));
        malformed(()->new Actor(OWNER,"../other",true,false,true,1));malformed(()->new Grant(GRANT,CONTROL,OWNER,STAFF,0,Set.of(HALL),grant().capabilities(),0,false));
        Set<UUID> mutable=new HashSet<>(Set.of(HALL));Grant frozen=new Grant(GRANT,CONTROL,OWNER,STAFF,2,mutable,grant().capabilities(),0,false);mutable.clear();check(frozen.halls().contains(HALL),"grant scope is immutable after construction");
        System.out.println("OperationsPolicyChecks passed "+checks+" checks");
    }
}
