package local.cinemamanager.catalog;

import local.cinemamanager.catalog.FilmCatalog.*;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** Pure snapshot/content equivalence checks; no world access or fabricated runtime permission. */
public final class FilmPosterSelectionChecks {
    private static int checks;
    private static final String PUBLIC="resource:mycinema:textures/block/info.png";
    private static final String ASSET="mycinema-asset:"+"a".repeat(64);
    private static final Predicate<String> PUBLIC_SOURCE=s->s.equals(PUBLIC)||s.equals(ASSET);
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Film film(Film base,long revision,String title,String poster,List<Segment> segments){return new Film(base.id(),revision,title,poster,segments);}
    private static Segment segment(String source,long duration){return new Segment(source,new Observation(false,duration,new Provenance(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),source,UUID.randomUUID().toString())));}
    public static void main(String[] args){
        Segment a=segment("water://a",6000),b=segment("water://b",7000);
        Film saved=new Film(UUID.randomUUID().toString(),2,"Saved film","",List.of(a,b,a));
        Snapshot snapshot=new Snapshot(UUID.randomUUID().toString(),saved,3000);
        Film later=film(saved,3,saved.title(),PUBLIC,saved.segments());
        check(FilmPosterSelection.candidate(saved,later,PUBLIC_SOURCE)==later,"poster-only late addition may supply display metadata");
        Film local=film(saved,2,saved.title(),"file:/private-poster.png",saved.segments());
        Film uploaded=film(saved,3,saved.title(),ASSET,saved.segments());
        check(FilmPosterSelection.candidate(local,uploaded,PUBLIC_SOURCE)==uploaded,"uploaded asset may replace only an unpublishable saved local poster");
        check(snapshot.film()==saved&&snapshot.film().poster().isEmpty()&&snapshot.totalDurationMs()==25000,"selection preserves snapshot, repeated clips and transition time");
        check(FilmPosterSelection.candidate(saved,null,PUBLIC_SOURCE)==null,"deleted film cannot supply a fallback");
        check(FilmPosterSelection.candidate(saved,film(saved,2,saved.title(),PUBLIC,saved.segments()),PUBLIC_SOURCE)==null,"same revision cannot supply fallback");
        check(FilmPosterSelection.candidate(saved,film(saved,1,saved.title(),PUBLIC,saved.segments()),PUBLIC_SOURCE)==null,"older revision cannot supply fallback");
        check(FilmPosterSelection.candidate(saved,new Film(UUID.randomUUID().toString(),3,saved.title(),PUBLIC,saved.segments()),PUBLIC_SOURCE)==null,"different film ID denied");
        check(FilmPosterSelection.candidate(saved,film(saved,3,"Changed title",PUBLIC,saved.segments()),PUBLIC_SOURCE)==null,"changed title denied");
        check(FilmPosterSelection.candidate(saved,film(saved,3,saved.title(),PUBLIC,List.of(a,a,b)),PUBLIC_SOURCE)==null,"changed source order denied");
        check(FilmPosterSelection.candidate(saved,film(saved,3,saved.title(),PUBLIC,List.of(a,b)),PUBLIC_SOURCE)==null,"removed duplicate denied");
        Segment newEvidence=segment(a.sourceKey(),6000);
        check(FilmPosterSelection.candidate(saved,film(saved,3,saved.title(),PUBLIC,List.of(newEvidence,b,newEvidence)),PUBLIC_SOURCE)==null,"same source and duration with changed provenance denied");
        Segment newDuration=new Segment(a.sourceKey(),new Observation(false,9000,a.observation().provenance()));
        check(FilmPosterSelection.candidate(saved,film(saved,3,saved.title(),PUBLIC,List.of(newDuration,b,newDuration)),PUBLIC_SOURCE)==null,"changed metadata denied");
        check(FilmPosterSelection.candidate(saved,film(saved,3,saved.title(),"file:/other.png",saved.segments()),PUBLIC_SOURCE)==null,"current local path is not publishable");
        Film pinned=film(saved,2,saved.title(),PUBLIC,saved.segments());
        check(FilmPosterSelection.candidate(pinned,uploaded,PUBLIC_SOURCE)==pinned,"explicit saved public poster stays pinned independently of mutable current poster");
        check(FilmPosterSelection.candidate(pinned,null,PUBLIC_SOURCE)==pinned,"deleting a film preserves its own saved explicit public poster");
        System.out.println("PASS: "+checks+" display-only film poster selection checks.");
    }
}
