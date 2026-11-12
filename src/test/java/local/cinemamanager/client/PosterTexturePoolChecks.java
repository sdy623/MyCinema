package local.cinemamanager.client;

import java.util.ArrayList;
import java.util.List;

/** Resource ownership and per-publication authority checks without Minecraft, GL, files or network. */
public final class PosterTexturePoolChecks {
    private static int checks;
    private static void check(boolean yes,String reason){checks++;if(!yes)throw new AssertionError(reason);}

    public static void main(String[] args) {
        sameSourceViewers();
        distinctSourceCapacity();
        cancelledLeader();
        redirectedContentProvenance();
        publicationAndLevelChanges();
        System.out.println("PASS: "+checks+" shared poster texture, capacity and independent authority checks.");
    }

    // Protocol outcomes below are simulated. Native acceptance must exercise the real per-tile reads.
    private static void sameSourceViewers() {
        List<String> closed=new ArrayList<>();
        var pool=new PosterTexturePool<String>(4,closed::add);
        var viewers=new ArrayList<PosterTexturePool.Lease<String>>();
        for(int i=0;i<8;i++)viewers.add(pool.acquire("https://image.invalid/poster.png?signature=one"));
        check(viewers.stream().allMatch(v->v!=null)&&pool.size()==1,"eight panels with one approved URI reserve one physical image slot");
        var first=viewers.getFirst();
        check(pool.beginLoad(first,true)&&pool.needsDecode(first),"first publication owns the initial decode");
        check(!pool.beginLoad(viewers.get(1),true),"another same-source read cannot create a simultaneous decoded completion");
        check(!pool.install(first,"unauthorized pixels",true),"reserving a source does not authorize a texture upload");
        check(pool.authorize(first,"same bytes",true)&&pool.install(first,"texture one",true),"own successful read can publish pixels");
        pool.endLoad(first);
        int simulatedSuccessfulReads=1;
        for(int pass=0;pass<3;pass++)for(int i=0;i<viewers.size();i++){
            var viewer=viewers.get(i);
            if(pass==0&&i>0){
                check(pool.get(viewer,true)==null,"matching URL cannot borrow before this tile's own read finishes");
                check(pool.beginLoad(viewer,true),"each waiting publication eventually gets a read turn");
                simulatedSuccessfulReads++;
                check(!pool.needsDecode(viewer),"authorized sharing does not allocate another decoded image");
                check(pool.authorize(viewer,"same bytes",true),"independent successful read authorizes only this publication");
                pool.endLoad(viewer);
            }
            check("texture one".equals(pool.get(viewer,true)),"repeated lookups preserve all eight ready viewers");
        }
        check(simulatedSuccessfulReads==8&&pool.size()==1,"eight independent reads still use one physical texture");
        for(int i=0;i<7;i++){pool.release(viewers.get(i));check(closed.isEmpty(),"another live viewer prevents premature texture disposal");}
        pool.release(viewers.get(7));pool.release(viewers.get(7));pool.clear();
        check(closed.equals(List.of("texture one"))&&pool.size()==0,"last release closes the shared texture exactly once");
    }

    private static void distinctSourceCapacity() {
        List<String> closed=new ArrayList<>();
        var pool=new PosterTexturePool<String>(4,closed::add);
        var viewers=new ArrayList<PosterTexturePool.Lease<String>>();
        for(int i=0;i<4;i++){
            var viewer=pool.acquire("source "+i);viewers.add(viewer);
            check(pool.authorize(viewer,"same bytes",true)&&pool.install(viewer,"texture "+i,true),"first four distinct textures fit the original budget");
        }
        for(int frame=0;frame<200;frame++){
            check(pool.acquire("source 4")==null,"fifth distinct source reports capacity immediately instead of becoming a stuck pending lease");
            for(int i=0;i<4;i++)check(("texture "+i).equals(pool.get(viewers.get(i),true)),"capacity checks do not thrash active textures");
        }
        pool.release(viewers.getFirst());
        var fifth=pool.acquire("source 4");
        check(fifth!=null&&pool.size()==4,"fifth source makes progress immediately when an unused source releases its slot");
        check(pool.get(fifth,true)==null&&pool.needsDecode(fifth),"admission cannot inherit the evicted source's authority or pixels");
        check(pool.authorize(fifth,"same bytes",true)&&pool.install(fifth,"texture 4",true),"admitted fifth source can finish its own load");
        pool.clear();
        check(closed.size()==5&&closed.stream().distinct().count()==5,"all five lifetime textures close once while at most four coexist");
    }

    private static void cancelledLeader() {
        List<String> closed=new ArrayList<>();
        var pool=new PosterTexturePool<String>(4,closed::add);
        var old=pool.acquire("private asset");
        var waiting=pool.acquire("private asset");
        check(pool.beginLoad(old,true),"one current lease begins a private-asset read");
        var late=new PosterCompletion<String>();
        pool.release(old);
        check(pool.beginLoad(waiting,true)&&pool.needsDecode(waiting),"cancelled or denied leader cannot starve another independently authorized reader");
        pool.endLoad(old);
        var third=pool.acquire("private asset");
        check(!pool.beginLoad(third,true),"late cancellation cannot clear the new reader's work ownership");
        check(late.complete("stale pixels",null),"simulate a worker completing after its publication was removed");
        var result=late.take();
        check(!pool.authorize(old,"same bytes",true)&&!pool.install(old,result.value(),true)&&pool.get(old,true)==null,
                "stale completion cannot authorize, install or borrow through a released publication");
        check(pool.authorize(waiting,"same bytes",true)&&pool.install(waiting,"current pixels",true),"replacement reader can publish its own successful result");
        pool.endLoad(waiting);
        check(pool.get(third,true)==null,"a private-asset neighbor still requires its own authorized download");
        check(!pool.beginLoad(third,false)&&!pool.authorize(third,"same bytes",false),"revoked publication cannot start or claim another tile's download");
        pool.release(waiting);pool.release(third);
        check(closed.equals(List.of("current pixels")),"a stale completion never becomes a leaked or double-closed texture");
    }

    private static void redirectedContentProvenance() {
        List<String> closed=new ArrayList<>();
        var pool=new PosterTexturePool<String>(4,closed::add);
        var authorA=pool.acquire("same redirecting URL");
        var authorB=pool.acquire("same redirecting URL");
        byte[] restrictedBytes={1,2,3,4},publicBytes={4,3,2,1};
        String restricted=PosterTexturePool.fingerprint(restrictedBytes);
        String allowed=PosterTexturePool.fingerprint(publicBytes);
        check(!restricted.equals(allowed)&&restricted.equals(PosterTexturePool.fingerprint(restrictedBytes.clone())),
                "content identity depends on exact bytes, not a mutable URL or redirect destination");
        check(pool.authorize(authorA,restricted,true)&&pool.install(authorA,"restricted target pixels",true),
                "first author publishes the bytes its redirect chain authorized");
        check(!pool.authorize(authorB,allowed,true)&&pool.get(authorB,true)==null,
                "a second author's successful read from another allowed target cannot expose the first target's different pixels");
        pool.release(authorB);
        check(closed.isEmpty()&&"restricted target pixels".equals(pool.get(authorA,true)),
                "content mismatch neither replaces another viewer's pixels nor disposes their texture");
        var sameBytesAllowedElsewhere=pool.acquire("same redirecting URL");
        check(pool.authorize(sameBytesAllowedElsewhere,PosterTexturePool.fingerprint(restrictedBytes.clone()),true)
                        &&"restricted target pixels".equals(pool.get(sameBytesAllowedElsewhere,true)),
                "sharing is safe when this viewer independently received all the identical bytes through its own authorized read");
        check(!pool.authorize(sameBytesAllowedElsewhere,allowed,true)&&pool.get(sameBytesAllowedElsewhere,true)==null,
                "changed read content withdraws a lease's earlier sharing authority");
        pool.release(sameBytesAllowedElsewhere);pool.release(authorA);
        check(closed.equals(List.of("restricted target pixels")),"content rejection preserves exact-once disposal");
    }

    private static void publicationAndLevelChanges() {
        List<String> closed=new ArrayList<>();
        var pool=new PosterTexturePool<String>(4,closed::add);
        var live=pool.acquire("same URI");
        var revoked=pool.acquire("same URI");
        pool.authorize(live,"same bytes",true);pool.install(live,"old level",true);pool.authorize(revoked,"same bytes",true);
        check(pool.get(revoked,false)==null&&pool.get(revoked,true)==null,"publication withdrawal invalidates authority even if a caller later supplies current=true");
        check("old level".equals(pool.get(live,true)),"revoking one tile leaves an independently authorized neighbor usable");
        pool.release(revoked);
        var newToken=pool.acquire("same URI");
        check(pool.get(newToken,true)==null,"a replacement publication token never inherits its predecessor's permission");
        pool.clear();pool.clear();
        check(pool.get(live,true)==null&&pool.get(newToken,true)==null,"level clear invalidates every old lease");
        var nextLevel=pool.acquire("same URI");
        check(!pool.authorize(live,"same bytes",true)&&!pool.install(live,"late old world",true),"an old world's completion cannot overwrite a same-URI slot in another world");
        check(pool.authorize(nextLevel,"same bytes",true)&&pool.install(nextLevel,"new level",true),"new world gets independent pixels and authority");
        pool.release(live);pool.release(newToken);
        check("new level".equals(pool.get(nextLevel,true))&&closed.equals(List.of("old level")),"late old-world cleanup cannot release the new world's texture");
        var foreign=new PosterTexturePool<String>(4,closed::add);
        foreign.release(nextLevel);
        check("new level".equals(pool.get(nextLevel,true)),"another pool cannot release a lease it does not own");
        pool.release(nextLevel);pool.clear();
        check(closed.equals(List.of("old level","new level")),"level clear and later reference cleanup close each generation exactly once");
    }
}
