package local.cinemamanager.library;

import local.cinemamanager.catalog.FilmCatalog;
import java.util.*;

public final class LocalProgrammeConsentsChecks {
    private static int checks;
    private static void yes(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void rejects(Runnable action,String label){checks++;try{action.run();}catch(IllegalArgumentException accepted){return;}throw new AssertionError(label);}
    private static FilmCatalog.Segment segment(String url,UUID owner,UUID manager,UUID terminal,UUID proof){return new FilmCatalog.Segment(url,new FilmCatalog.Observation(false,6000,new FilmCatalog.Provenance(owner.toString(),manager.toString(),terminal.toString(),url,proof.toString())));}
    public static void main(String[]args){
        UUID owner=UUID.randomUUID(),manager=UUID.randomUUID(),terminal=UUID.randomUUID();String hall=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        var a=segment("file:/qa/a.mkv",owner,manager,terminal,UUID.randomUUID());var b=segment("media/b.mkv",owner,manager,terminal,UUID.randomUUID());
        var film=new FilmCatalog.Film(id,1,"QA film","",List.of(a,b,b));var store=new LocalProgrammeConsents();
        yes(!store.permits(owner,manager,terminal,hall,film,a.sourceKey()),"no default consent");store.authorize(owner,manager,terminal,hall,film);
        yes(store.size()==1,"one exact grant");for(var s:film.segments())yes(store.permits(owner,manager,terminal,hall,film,s.sourceKey()),"ordered source permitted");
        yes(!store.permits(owner,manager,terminal,hall,film,"file:/qa/other.mkv"),"other source rejected");
        yes(!store.permits(UUID.randomUUID(),manager,terminal,hall,film,a.sourceKey()),"changed owner");
        yes(!store.permits(owner,UUID.randomUUID(),terminal,hall,film,a.sourceKey()),"changed controller");
        yes(!store.permits(owner,manager,UUID.randomUUID(),hall,film,a.sourceKey()),"changed TMS");
        yes(!store.permits(owner,manager,terminal,UUID.randomUUID().toString(),film,a.sourceKey()),"changed hall");
        var edited=new FilmCatalog.Film(id,2,"QA revised","",film.segments());yes(!store.permits(owner,manager,terminal,hall,edited,a.sourceKey()),"new revision");
        var reordered=new FilmCatalog.Film(id,1,"QA film","",List.of(b,a,b));yes(!store.permits(owner,manager,terminal,hall,reordered,a.sourceKey()),"order binds scope");
        var proofChanged=new FilmCatalog.Film(id,1,"QA film","",List.of(segment(a.sourceKey(),owner,manager,terminal,UUID.randomUUID()),b,b));yes(!store.permits(owner,manager,terminal,hall,proofChanged,a.sourceKey()),"evidence identity binds scope");
        var loaded=LocalProgrammeConsents.load(store.save());yes(loaded.permits(owner,manager,terminal,hall,film,b.sourceKey()),"save reload");yes(loaded.revokeHall(hall)==1&&!loaded.permits(owner,manager,terminal,hall,film,a.sourceKey()),"revocation");
        yes(LocalProgrammeConsents.load("").size()==0,"legacy empty NBT has no grant");rejects(()->LocalProgrammeConsents.load("{\"version\":2,\"consents\":[]}"),"unknown schema");
        rejects(()->LocalProgrammeConsents.load("{\"version\":1,\"consents\":[null]}"),"null record");
        for(int i=2;i<=LocalProgrammeConsents.MAX_RECORDS;i++)store.authorize(owner,manager,terminal,hall,new FilmCatalog.Film(id,i,"QA","",film.segments()));
        String before=store.save();rejects(()->store.authorize(owner,manager,terminal,hall,new FilmCatalog.Film(id,129,"QA","",film.segments())),"bounded grants");yes(before.equals(store.save()),"overflow mutation is atomic");
        yes(LocalProgrammeConsents.load(store.save()).size()==128,"all bounded grants retained");
        System.out.println("PASS: "+checks+" exact local programme consent assertions; identity, ordered source/evidence, immutable revision, safe old NBT, revocation and atomic bounds.");
    }
}
