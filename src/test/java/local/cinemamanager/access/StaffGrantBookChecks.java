package local.cinemamanager.access;

import java.util.*;

/** Pure policy checks: server grants authorize a cinema and role, never a card's self-description. */
public final class StaffGrantBookChecks {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static StaffGrantBook.Scope scope(){return new StaffGrantBook.Scope(UUID.randomUUID(),UUID.randomUUID(),11,UUID.randomUUID(),UUID.randomUUID(),22,UUID.randomUUID(),33,UUID.randomUUID());}
    private static StaffGrantBook.Scope reader(StaffGrantBook.Scope s){return new StaffGrantBook.Scope(s.controller(),s.controllerOwner(),s.controllerPos(),UUID.randomUUID(),UUID.randomUUID(),99,UUID.randomUUID(),100,UUID.randomUUID());}
    public static void main(String[]args){
        StaffGrantBook book=new StaffGrantBook();var a=scope();var b=reader(a);UUID player=UUID.randomUUID(),issuer=UUID.randomUUID(),colleague=UUID.randomUUID();
        var first=book.issue(player,issuer,a,StaffRole.ENTRY);var second=book.issue(colleague,issuer,b,StaffRole.SERVICE);
        check(book.authorize(first.card(),player,a,StaffRole.ENTRY),"issued credential opens first reader");
        check(book.authorize(first.card(),player,b,StaffRole.ENTRY),"same-cinema same-type reader accepts existing card");
        check(!book.authorize(first.card(),player,b,StaffRole.SERVICE),"ENTRY cannot enter SERVICE reader");
        check(book.authorize(second.card(),colleague,a,StaffRole.ENTRY)&&book.authorize(second.card(),colleague,b,StaffRole.SERVICE),"SERVICE opens both levels across this cinema");
        check(book.forCinema(a).size()==2&&book.forCinema(b).size()==2,"different reader editors expose one cinema roster");
        check(!book.authorize(first.card(),UUID.randomUUID(),a,StaffRole.ENTRY),"stolen or copied physical card cannot change holder");
        check(!book.authorize(UUID.randomUUID(),player,a,StaffRole.ENTRY),"NBT alone cannot create a grant");
        check(!book.authorize(first.card(),player,a,null),"invalid required role denied");
        check(!book.authorize(first.card(),player,null,StaffRole.ENTRY),"no live reader scope denied");
        for(var foreign:List.of(
                new StaffGrantBook.Scope(UUID.randomUUID(),a.controllerOwner(),a.controllerPos(),a.reader(),a.readerOwner(),a.readerPos(),a.door(),a.doorPos(),a.revision()),
                new StaffGrantBook.Scope(a.controller(),UUID.randomUUID(),a.controllerPos(),a.reader(),a.readerOwner(),a.readerPos(),a.door(),a.doorPos(),a.revision()),
                new StaffGrantBook.Scope(a.controller(),a.controllerOwner(),a.controllerPos()+1,a.reader(),a.readerOwner(),a.readerPos(),a.door(),a.doorPos(),a.revision())))
            check(!book.authorize(first.card(),player,foreign,StaffRole.ENTRY),"cinema controller identity owner and position remain exact");
        var replaced=book.issue(player,issuer,b,StaffRole.SERVICE);
        check(!book.authorize(first.card(),player,a,StaffRole.ENTRY),"explicit replacement revokes old token cinema-wide");
        check(book.authorize(replaced.card(),player,a,StaffRole.SERVICE)&&book.authorize(replaced.card(),player,b,StaffRole.SERVICE),"replacement from second reader works across this cinema");
        check(book.authorize(second.card(),colleague,a,StaffRole.SERVICE),"replacing one employee never revokes another");
        var foreign=scope();var otherCinema=book.issue(player,issuer,foreign,StaffRole.ENTRY);
        check(book.authorize(replaced.card(),player,a,StaffRole.SERVICE)&&book.authorize(otherCinema.card(),player,foreign,StaffRole.ENTRY),"other cinema issuance is independent");
        StaffGrantBook restored=new StaffGrantBook();restored.restore(book.all());
        check(restored.authorize(replaced.card(),player,b,StaffRole.SERVICE),"restart retains type-based cinema authority");
        restored.restore(List.of(replaced,replaced));check(!restored.authorize(replaced.card(),player,a,StaffRole.ENTRY),"duplicate persisted token fails closed");
        // Persisted pre-migration grants use this same full Scope. Authority comes from server records only.
        restored.restore(List.of(first,second));check(restored.authorize(first.card(),player,b,StaffRole.ENTRY),"legacy server grant migrates across same-cinema readers");
        check(!restored.authorize(first.card(),player,b,StaffRole.SERVICE),"migration never upgrades old ENTRY role");
        check(book.revokeCinema(b,player)&&!book.authorize(replaced.card(),player,a,StaffRole.ENTRY),"revoke from any reader immediately denies all readers of that cinema");
        check(book.authorize(second.card(),colleague,a,StaffRole.SERVICE)&&book.authorize(otherCinema.card(),player,foreign,StaffRole.ENTRY),"revocation preserves other employees and cinemas");
        StaffGrantBook capacity=new StaffGrantBook();for(int i=0;i<StaffGrantBook.MAX_CINEMA_GRANTS;i++)capacity.issue(UUID.randomUUID(),issuer,reader(a),StaffRole.ENTRY);
        try{capacity.issue(UUID.randomUUID(),issuer,b,StaffRole.ENTRY);throw new AssertionError("cinema limit accepted");}catch(IllegalArgumentException expected){check(expected.getMessage().equals("mycinema.access.limit"),"bounded shared cinema roster");}
        var old=capacity.forCinema(a).getFirst();var renewed=capacity.issue(old.player(),issuer,b,StaffRole.SERVICE);
        check(capacity.forCinema(a).size()==StaffGrantBook.MAX_CINEMA_GRANTS&&capacity.authorize(renewed.card(),old.player(),a,StaffRole.SERVICE),"same-player replacement works at cinema capacity");
        List<StaffGrantBook.Grant> legacyLarge=new ArrayList<>();
        for(int i=0;i<StaffGrantBook.MAX_CINEMA_GRANTS+2;i++)legacyLarge.add(new StaffGrantBook.Grant(UUID.randomUUID(),UUID.randomUUID(),issuer,reader(a),StaffRole.ENTRY));
        restored.restore(legacyLarge);check(restored.all().size()==legacyLarge.size(),"legacy multi-reader employees are not dropped by the new issuance bound");
        var legacyEmployee=legacyLarge.getFirst();var legacyReplacement=restored.issue(legacyEmployee.player(),issuer,b,StaffRole.SERVICE);
        check(restored.all().size()==legacyLarge.size()&&restored.authorize(legacyReplacement.card(),legacyEmployee.player(),a,StaffRole.SERVICE),"replacement still works for a retained over-limit legacy cinema");
        tabletChecks();
        System.out.println("PASS: "+assertions+" cinema-role card and activated-tablet authority, migration, replacement, revocation, persistence, and bounds assertions.");
    }
    private static void tabletChecks(){
        TabletAccessBook book=new TabletAccessBook();UUID player=UUID.randomUUID();
        var scope=new TabletAccessBook.Scope(UUID.randomUUID(),player,41);
        var first=book.activate(player,scope);
        check(book.registered(first.token(),player)&&book.authorize(first.token(),player,scope),"terminal-activated tablet permits its bound player and exact controller");
        check(!book.authorize(first.token(),UUID.randomUUID(),scope),"copied tablet cannot change its bound holder");
        check(!book.authorize(UUID.randomUUID(),player,scope),"NBT alone cannot create tablet access");
        check(!book.authorize(first.token(),player,new TabletAccessBook.Scope(UUID.randomUUID(),player,41)),"foreign or replaced controller denies tablet");
        check(!book.authorize(first.token(),player,new TabletAccessBook.Scope(scope.controller(),UUID.randomUUID(),41)),"controller ownership change denies tablet");
        check(!book.authorize(first.token(),player,new TabletAccessBook.Scope(scope.controller(),player,42)),"controller movement denies tablet");
        check(!book.authorize(first.token(),player,null),"missing live controller fails closed");
        check(book.authorize(first.token(),player,scope,TabletAccessBook.CURRENT_VERSION),"current authorization version accepted");
        check(!book.authorize(first.token(),player,scope,0),"unknown legacy item version cannot authorize itself");
        check(!book.authorize(first.token(),player,scope,TabletAccessBook.CURRENT_VERSION+1),"future authorization version fails closed");
        var replacement=book.activate(player,scope);
        check(!book.registered(first.token(),player)&&book.authorize(replacement.token(),player,scope),"terminal reactivation revokes all copies of previous tablet activation");
        TabletAccessBook restored=new TabletAccessBook();restored.restore(book.all());
        check(restored.authorize(replacement.token(),player,scope),"server restart restores exact tablet registration");
        restored.restore(List.of(replacement,replacement));
        check(!restored.registered(replacement.token(),player),"duplicate persisted tablet token fails closed");
        check(!book.revoke(replacement.token(),UUID.randomUUID())&&book.registered(replacement.token(),player),"another holder cannot revoke original user's tablet");
        check(book.revoke(replacement.token(),player)&&!book.authorize(replacement.token(),player,scope),"unbind revokes activation and copies immediately");
        var otherScope=new TabletAccessBook.Scope(UUID.randomUUID(),player,42);var other=book.activate(player,otherScope);var again=book.activate(player,scope);
        check(book.authorize(other.token(),player,otherScope)&&book.authorize(again.token(),player,scope),"separately activated tablets retain their own cinema scopes");
        book.revoke(again.token(),player);check(book.authorize(other.token(),player,otherScope),"revoking one cinema tablet preserves another independently activated tablet");
        TabletAccessBook capacity=new TabletAccessBook();
        for(int i=0;i<TabletAccessBook.MAX_GRANTS;i++)capacity.activate(UUID.randomUUID(),scope);
        try{capacity.activate(UUID.randomUUID(),scope);throw new AssertionError("tablet limit accepted");}catch(IllegalArgumentException expected){check(expected.getMessage().equals("mycinema.access.limit"),"bounded server tablet registry");}
        var full=capacity.all().iterator().next();var renewed=capacity.activate(full.player(),scope);
        check(capacity.authorize(renewed.token(),full.player(),scope)&&!capacity.registered(full.token(),full.player()),"reactivation works at tablet capacity");
        var differentCinema=new TabletAccessBook.Scope(UUID.randomUUID(),full.player(),57);
        var rebound=capacity.activate(full.player(),differentCinema,renewed.token());
        check(capacity.all().size()==TabletAccessBook.MAX_GRANTS&&capacity.authorize(rebound.token(),full.player(),differentCinema)&&!capacity.registered(renewed.token(),full.player()),"same physical tablet can atomically rebind to another cinema at full capacity");
        try{capacity.activate(full.player(),scope,UUID.randomUUID());throw new AssertionError("invalid replacement token freed capacity");}catch(IllegalArgumentException expected){check(capacity.authorize(rebound.token(),full.player(),differentCinema),"failed activation preserves previous controller grant");}
        UUID stranger=UUID.randomUUID();try{capacity.activate(stranger,scope,rebound.token());throw new AssertionError("another player's token freed capacity");}catch(IllegalArgumentException expected){check(capacity.authorize(rebound.token(),full.player(),differentCinema),"capacity credit cannot revoke another player's activation");}
        TabletAccessBook sourceDimension=new TabletAccessBook();var source=sourceDimension.activate(full.player(),scope);
        try{capacity.activate(full.player(),scope);throw new AssertionError("full destination accepted new dimension grant");}catch(IllegalArgumentException expected){check(sourceDimension.authorize(source.token(),full.player(),scope),"full destination leaves source-dimension registration intact before revocation");}
    }
}
