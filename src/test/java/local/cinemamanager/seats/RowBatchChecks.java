package local.cinemamanager.seats;

import com.google.gson.*;
import java.util.*;

/** Pure model checks: no Minecraft bootstrap, world, renderer or network connection is required. */
public final class RowBatchChecks {
    private static final String HALL=uid(80000),OTHER_HALL=uid(80001);
    private static int checks;
    private static String uid(long value){return new UUID(0,value+1).toString();}
    private static void check(boolean condition,String detail){checks++;if(!condition)throw new AssertionError(detail);}
    private static void reject(String code,Runnable action){
        try{action.run();}catch(IllegalArgumentException expected){check(expected.getMessage().equals("mycinema.seats."+code),"expected "+code+", got "+expected.getMessage());return;}
        throw new AssertionError("Expected rejection: "+code);
    }
    private static void unchanged(SeatRegistry registry,String before,long revision){
        check(registry.revision()==revision&&registry.encode().equals(before),"failed operation preserves exact serialized bytes and revision");
    }
    private static RowBatch.Request request(int row,int start,int direction,int end,GuidePreset.RowScheme scheme){return new RowBatch.Request(HALL,"Lower",row,start,direction,end,scheme);}
    private static RowBatch.Request request(int row,int start,int direction,int end){return request(row,start,direction,end,GuidePreset.RowScheme.NUMERIC);}
    private static RowBatch.Candidate chair(long position){return RowBatch.Candidate.chair(position,uid(position),"north");}
    private static RowBatch.Preview preview(SeatRegistry registry,RowBatch.Request request,RowBatch.Candidate... cells){return RowBatch.preview(registry,registry.revision(),request,List.of(cells));}
    private static void hasError(RowBatch.Preview preview,String code){check(!preview.canCommit()&&preview.errors().stream().anyMatch(issue->issue.code().equals("mycinema.seats."+code)),"blocking preview code "+code);}
    private static void blocked(SeatRegistry registry,RowBatch.Preview preview,String code){
        hasError(preview,code);String before=registry.encode();long revision=registry.revision();
        reject(preview.errors().getFirst().code().substring("mycinema.seats.".length()),()->RowBatch.commit(registry,preview,preview.candidates()));unchanged(registry,before,revision);
    }
    private static SeatRegistry.Seat physical(SeatRegistry registry,long position,int row,int number){
        return registry.put(registry.revision(),"",HALL,"Lower",row,number,position,SeatRegistry.Status.AVAILABLE,SeatRegistry.PHYSICAL_BLOCK,uid(position),GuidePreset.RowScheme.NUMERIC);
    }

    private static void serializationAndIdentity(){
        String old="{\"schema\":1,\"revision\":4,\"seats\":[{\"id\":\""+uid(90000)+"\",\"hall\":\""+HALL+"\",\"zone\":\"Lower\",\"row\":27,\"number\":12,\"position\":42,\"status\":\"DISABLED\",\"block\":\"minecraft:oak_stairs\"}]}";
        SeatRegistry migrated=SeatRegistry.decode(old);SeatRegistry.Seat legacy=migrated.seats().getFirst();
        check(!legacy.verifiedIdentity()&&legacy.seatUid().isEmpty()&&legacy.rowScheme()==GuidePreset.RowScheme.NUMERIC&&legacy.row()==27&&legacy.rowLabel().equals("27"),"schema1 stays explicitly unverified and numeric");
        check(SeatRegistry.decode(old.replace(",\"block\":\"minecraft:oak_stairs\"","")).seats().getFirst().block().isEmpty(),"schema1 without optional block remains readable");
        SeatRegistry.Seat legacyWire=SeatRegistry.JSON.fromJson(JsonParser.parseString(old).getAsJsonObject().getAsJsonArray("seats").get(0),SeatRegistry.Seat.class);
        check(legacyWire.seatUid().isEmpty()&&legacyWire.rowScheme()==GuidePreset.RowScheme.NUMERIC,"old wire records receive safe identity/display defaults");
        String before=migrated.encode();
        reject("identity_immutable",()->migrated.put(4,legacy.id(),HALL,"Lower",27,12,42,SeatRegistry.Status.DISABLED,SeatRegistry.PHYSICAL_BLOCK,uid(42),GuidePreset.RowScheme.LETTERS));unchanged(migrated,before,4);
        SeatRegistry.Seat edit=migrated.put(4,legacy.id(),HALL,"Lower",27,13,42,SeatRegistry.Status.MAINTENANCE);
        check(edit.seatUid().isEmpty()&&edit.row()==27&&edit.rowScheme()==GuidePreset.RowScheme.NUMERIC,"legacy edits cannot adopt an actual chair implicitly");
        check(SeatRegistry.decode(migrated.encode()).seats().equals(migrated.seats()),"mixed-compatible schema2 round trip");
        SeatRegistry registry=new SeatRegistry();
        RowBatch.Preview preview=preview(registry,request(27,1,1,0,GuidePreset.RowScheme.LETTERS),chair(100));
        SeatRegistry.Seat seat=RowBatch.commit(registry,preview,preview.candidates()).seats().getFirst();
        check(!seat.id().equals(seat.seatUid())&&seat.seatUid().equals(uid(100))&&seat.row()==27&&seat.rowLabel().equals("AA"),"inventory identity and immutable physical UID are independent");
        check(registry.encode().contains("\"schema\":2")&&registry.encode().contains("\"seat_uid\":")&&!registry.encode().contains("\"seatUid\":"),"explicit serialized seat_uid contract");
        check(SeatRegistry.decode(registry.encode()).seats().equals(registry.seats()),"UID and display scheme survive reload");
        SeatRegistry.Seat updated=registry.put(1,seat.id(),HALL,"Lower",27,2,100,SeatRegistry.Status.DISABLED);
        check(updated.seatUid().equals(seat.seatUid())&&updated.rowScheme()==GuidePreset.RowScheme.LETTERS,"old edit overloads preserve physical UID and display scheme");
        before=registry.encode();final String unchangedBefore=before;
        reject("identity_immutable",()->registry.put(2,seat.id(),HALL,"Lower",27,2,100,SeatRegistry.Status.DISABLED,SeatRegistry.PHYSICAL_BLOCK,uid(101),GuidePreset.RowScheme.LETTERS));unchanged(registry,unchangedBefore,2);
        reject("identity_immutable",()->registry.put(2,seat.id(),HALL,"Lower",27,2,101,SeatRegistry.Status.DISABLED));unchanged(registry,unchangedBefore,2);
        reject("duplicate_seat_uid",()->registry.put(2,"",OTHER_HALL,"Other",8,8,102,SeatRegistry.Status.AVAILABLE,SeatRegistry.PHYSICAL_BLOCK,seat.seatUid(),GuidePreset.RowScheme.NUMERIC));unchanged(registry,unchangedBefore,2);
        for(String bad:List.of("not-a-uuid","00000000-0000-0000-0000-1","00000000-0000-0000-0000-0000000000AB")){
            reject("row_invalid_uid",()->registry.put(2,"",HALL,"Lower",28,1,200,SeatRegistry.Status.AVAILABLE,SeatRegistry.PHYSICAL_BLOCK,bad,GuidePreset.RowScheme.NUMERIC));
        }
        reject("row_wrong_block",()->registry.put(2,"",HALL,"Lower",28,1,200,SeatRegistry.Status.AVAILABLE,"minecraft:oak_stairs",uid(200),GuidePreset.RowScheme.NUMERIC));unchanged(registry,unchangedBefore,2);
        for(String malformed:List.of(
                registry.encode().replace(seat.seatUid(),"not-a-uuid"),
                registry.encode().replace("\"rowScheme\":\"LETTERS\"","\"rowScheme\":\"ROMAN\""),
                registry.encode().replace("\"seat_uid\":\""+seat.seatUid()+"\"","\"seat_uid\":null"),
                registry.encode().replace("\"revision\":2","\"revision\":\"2\""),
                registry.encode().replace("\"schema\":2","\"schema\":2.5"),
                registry.encode().replace("\"row\":27","\"row\":\"27\"")))reject("invalid_saved",()->SeatRegistry.decode(malformed));
        check(new SeatRegistry.Seat(uid(91000),HALL,"Lower",1,1,2,SeatRegistry.Status.AVAILABLE,"minecraft:oak_stairs").seatUid().isEmpty(),"old constructor remains available");
        for(int row:new int[]{1,26,27,702,703,999}){
            String expected=switch(row){case 1->"A";case 26->"Z";case 27->"AA";case 702->"ZZ";case 703->"AAA";default->"ALK";};
            check(request(row,1,1,0,GuidePreset.RowScheme.LETTERS).rowLabel().equals(expected),"plain alphabetic label "+row);
            check(request(row,1,1,0).rowLabel().equals(Integer.toString(row)),"numeric label "+row);
        }
    }

    private static void numberingAndSkips(){
        SeatRegistry registry=new SeatRegistry();SeatRegistry.Seat existing=physical(registry,102,26,77);
        List<RowBatch.Candidate> source=new ArrayList<>(List.of(chair(100),RowBatch.Candidate.gap(101),chair(102),chair(103)));
        RowBatch.Preview preview=RowBatch.preview(registry,1,request(26,3,1,4,GuidePreset.RowScheme.LETTERS),source);
        check(preview.canCommit()&&preview.count()==2&&preview.gaps()==1&&preview.skipped()==1,"preview explicitly reports gaps and indexed skips");
        check(preview.assignments().stream().map(RowBatch.Assignment::number).toList().equals(List.of(3,4)),"gaps and existing chairs consume no number");
        check(preview.warnings().size()==1&&preview.warnings().getFirst().code().equals("mycinema.seats.row_existing_skipped"),"existing identity skip is visible");
        source.clear();check(preview.candidates().size()==4,"preview freezes caller list");
        try{preview.assignments().clear();throw new AssertionError("Mutable preview assignments");}catch(UnsupportedOperationException expected){checks++;}
        RowBatch.Committed result=RowBatch.commit(registry,preview,preview.candidates());
        check(result.count()==2&&result.revision()==2&&registry.revision()==2&&registry.seats().size()==3,"one complete row changes revision exactly once");
        check(registry.find(existing.id()).equals(existing),"indexed chair never overwritten");
        check(result.seats().stream().allMatch(seat->seat.row()==26&&seat.rowLabel().equals("Z")&&seat.verifiedIdentity()),"explicit display scheme is saved independently from numeric row identity");
        String before=registry.encode();reject("row_stale",()->RowBatch.commit(registry,preview,preview.candidates()));unchanged(registry,before,2);

        SeatRegistry legacy=new SeatRegistry();SeatRegistry.Seat old=legacy.put(0,"",HALL,"Lower",6,30,100,SeatRegistry.Status.DISABLED,"minecraft:oak_stairs");
        RowBatch.Preview legacyPreview=preview(legacy,request(6,3,-1,1),chair(101),chair(100),RowBatch.Candidate.gap(102),chair(103),chair(104));
        check(legacyPreview.canCommit()&&legacyPreview.assignments().stream().map(RowBatch.Assignment::number).toList().equals(List.of(3,2,1)),"descending row respects ordered chairs and skips");
        check(legacyPreview.warnings().getFirst().code().equals("mycinema.seats.row_legacy_unverified"),"legacy block-only skip has a visible identity warning");
        RowBatch.commit(legacy,legacyPreview,legacyPreview.candidates());check(legacy.find(old.id()).equals(old)&&!legacy.find(old.id()).verifiedIdentity(),"batch cannot silently adopt a legacy chair UID");
        for(int direction:new int[]{1,-1}){
            SeatRegistry edge=new SeatRegistry();int start=direction==1?999:1;
            RowBatch.Preview one=preview(edge,request(1,start,direction,0),chair(1));check(one.canCommit(),"inclusive natural numeric boundary");
            blocked(edge,preview(edge,request(1,start,direction,0),chair(1),chair(2)),"row_range");
        }
        SeatRegistry otherHall=new SeatRegistry();otherHall.put(0,"",OTHER_HALL,"Lower",1,1,99,SeatRegistry.Status.AVAILABLE);
        check(preview(otherHall,request(1,1,1,0),chair(1)).canCommit(),"number identity is scoped to exact hall and zone");
    }

    private static void invalidAndAtomic(){
        SeatRegistry registry=new SeatRegistry();registry.put(0,"",HALL,"lower",1,2,99,SeatRegistry.Status.MAINTENANCE);
        RowBatch.Preview conflict=preview(registry,request(1,1,1,0),chair(1),chair(2));
        check(conflict.assignments().getFirst().number()==1&&conflict.assignments().getLast().number()==2,"conflict preview does not silently renumber");
        blocked(registry,conflict,"row_number_conflict");
        SeatRegistry empty=new SeatRegistry();
        blocked(empty,preview(empty,request(1,1,1,0),chair(1),chair(1)),"row_duplicate_position");
        blocked(empty,preview(empty,request(1,1,1,0),RowBatch.Candidate.gap(1),chair(1)),"row_duplicate_position");
        blocked(empty,preview(empty,request(1,1,1,0),chair(1),RowBatch.Candidate.chair(2,uid(1),"north")),"row_duplicate_uid");
        for(String uid:List.of("","bad","00000000-0000-0000-0000-1","00000000-0000-0000-0000-0000000000AB"))blocked(empty,preview(empty,request(1,1,1,0),RowBatch.Candidate.chair(1,uid,"north")),"row_invalid_uid");
        blocked(empty,preview(empty,request(1,1,1,0),chair(1),RowBatch.Candidate.chair(2,uid(2),"east")),"row_mixed_facing");
        blocked(empty,preview(empty,request(1,1,1,0),RowBatch.Candidate.chair(1,uid(1),"up")),"row_invalid_facing");
        blocked(empty,preview(empty,request(1,1,1,0),chair(1),RowBatch.Candidate.blocked(2)),"row_blocked");
        blocked(empty,preview(empty,request(1,1,1,0),chair(1),RowBatch.Candidate.wrongBlock(2)),"row_wrong_block");
        blocked(empty,preview(empty,request(1,1,1,0),chair(1),RowBatch.Candidate.unloaded(2)),"row_unloaded");
        blocked(empty,preview(empty,request(1,1,1,0),new RowBatch.Candidate(1,null,"","")),"row_malformed");
        blocked(empty,preview(empty,request(1,1,1,0),new RowBatch.Candidate(1,RowBatch.Kind.GAP,uid(1),"north")),"row_malformed");
        blocked(empty,preview(empty,request(1,1,1,0),RowBatch.Candidate.gap(1)),"row_empty");
        blocked(empty,preview(empty,request(1,1,1,1),chair(1),chair(2)),"row_range");
        blocked(empty,preview(empty,request(1,3,-1,3),chair(1),chair(2)),"row_range");
        SeatRegistry indexed=new SeatRegistry();physical(indexed,1,1,10);
        blocked(indexed,preview(indexed,request(1,1,1,0),RowBatch.Candidate.chair(2,uid(1),"north")),"row_uid_conflict");
        blocked(indexed,preview(indexed,request(1,1,1,0),RowBatch.Candidate.chair(1,uid(2),"north"),chair(3)),"row_replaced_uid");
        blocked(indexed,preview(indexed,request(1,1,1,0),RowBatch.Candidate.gap(1),chair(3)),"row_missing_seat");
        blocked(indexed,preview(indexed,request(1,1,1,0),chair(1)),"row_empty");
        for(int[] values:new int[][]{{0,1,1,0},{1000,1,1,0},{1,0,1,0},{1,1000,1,0},{1,1,0,0},{1,1,2,0},{1,3,1,2},{1,3,-1,4},{1,1,1,-1},{1,1,1,1000}})reject("row_range",()->request(values[0],values[1],values[2],values[3]));
        reject("row_malformed",()->new RowBatch.Request(HALL,"Lower",1,1,1,0,null));
        reject("row_malformed",()->new RowBatch.Request("bad", "Lower",1,1,1,0,GuidePreset.RowScheme.NUMERIC));
        reject("row_malformed",()->new RowBatch.Request(HALL,"\n",1,1,1,0,GuidePreset.RowScheme.NUMERIC));
        reject("row_empty",()->RowBatch.preview(empty,0,request(1,1,1,0),List.of()));
        reject("row_malformed",()->RowBatch.preview(empty,0,request(1,1,1,0),Arrays.asList((RowBatch.Candidate)null)));
        reject("row_malformed",()->RowBatch.preview(empty,0,request(1,1,1,0),null));
        reject("row_stale",()->RowBatch.preview(empty,1,request(1,1,1,0),List.of(chair(1))));
        String before=empty.encode();
        SeatRegistry.Seat first=new SeatRegistry.Seat(uid(99000),HALL,"Lower",1,1,1,SeatRegistry.Status.AVAILABLE,SeatRegistry.PHYSICAL_BLOCK,uid(1));
        SeatRegistry.Seat duplicate=new SeatRegistry.Seat(uid(99001),HALL,"lower",1,1,2,SeatRegistry.Status.AVAILABLE,SeatRegistry.PHYSICAL_BLOCK,uid(2));
        reject("duplicate_number",()->empty.insertBatch(0,List.of(first,duplicate)));unchanged(empty,before,0);
        reject("duplicate_id",()->empty.insertBatch(0,List.of(first,first)));unchanged(empty,before,0);
        SeatRegistry.Seat invalid=new SeatRegistry.Seat(uid(99002),HALL,"Lower",1,2,2,SeatRegistry.Status.AVAILABLE,SeatRegistry.PHYSICAL_BLOCK,"bad");
        reject("row_invalid_uid",()->empty.insertBatch(0,List.of(first,invalid)));unchanged(empty,before,0);
    }

    private static void staleAndBounded(){
        SeatRegistry registry=new SeatRegistry();RowBatch.Preview expected=preview(registry,request(1,1,1,0),chair(1),RowBatch.Candidate.gap(2),chair(3));String before=registry.encode();
        reject("row_replaced_uid",()->RowBatch.commit(registry,expected,List.of(chair(1),RowBatch.Candidate.gap(2),RowBatch.Candidate.chair(3,uid(4),"north"))));unchanged(registry,before,0);
        reject("row_stale_preview",()->RowBatch.commit(registry,expected,List.of(chair(3),RowBatch.Candidate.gap(2),chair(1))));unchanged(registry,before,0);
        reject("row_stale_preview",()->RowBatch.commit(registry,expected,List.of(chair(1),chair(2),chair(3))));unchanged(registry,before,0);
        reject("row_stale_preview",()->RowBatch.commit(registry,expected,List.of(RowBatch.Candidate.chair(1,uid(1),"south"),RowBatch.Candidate.gap(2),RowBatch.Candidate.chair(3,uid(3),"south"))));unchanged(registry,before,0);
        reject("row_unloaded",()->RowBatch.commit(registry,expected,List.of(chair(1),RowBatch.Candidate.gap(2),RowBatch.Candidate.unloaded(3))));unchanged(registry,before,0);
        RowBatch.Preview changedNumbers=new RowBatch.Preview(0,expected.request(),expected.candidates(),List.of(new RowBatch.Assignment(1,uid(1),8),new RowBatch.Assignment(3,uid(3),9)),expected.warnings(),expected.errors(),expected.gaps(),expected.skipped());
        reject("row_stale_preview",()->RowBatch.commit(registry,changedNumbers,expected.candidates()));unchanged(registry,before,0);
        RowBatch.Preview changedCounts=new RowBatch.Preview(0,expected.request(),expected.candidates(),expected.assignments(),expected.warnings(),expected.errors(),0,1);
        reject("row_stale_preview",()->RowBatch.commit(registry,changedCounts,expected.candidates()));unchanged(registry,before,0);
        registry.put(0,"",OTHER_HALL,"Other",1,1,99,SeatRegistry.Status.AVAILABLE);String changed=registry.encode();
        reject("row_stale",()->RowBatch.commit(registry,expected,expected.candidates()));unchanged(registry,changed,1);

        SeatRegistry bounded=new SeatRegistry();List<RowBatch.Candidate> cells=new ArrayList<>();cells.add(chair(1));
        for(int i=2;i<=RowBatch.MAX_CELLS;i++)cells.add(RowBatch.Candidate.gap(i));
        check(RowBatch.preview(bounded,0,request(1,1,1,0),cells).count()==1,"exact 4096-cell model limit accepts a bounded explicit span");
        cells.add(RowBatch.Candidate.gap(RowBatch.MAX_CELLS+1));reject("row_limit",()->RowBatch.preview(bounded,0,request(1,1,1,0),cells));unchanged(bounded,before,0);
        for(int i=0;i<SeatRegistry.MAX_SEATS-1;i++)bounded.put(i,"",HALL,"Capacity",i/999+1,i%999+1,10000+i,SeatRegistry.Status.AVAILABLE);
        blocked(bounded,preview(bounded,request(900,1,1,0),chair(1),chair(2)),"row_limit");
        RowBatch.Preview last=preview(bounded,request(900,1,1,0),chair(1));RowBatch.commit(bounded,last,last.candidates());
        check(bounded.seats().size()==SeatRegistry.MAX_SEATS&&bounded.revision()==SeatRegistry.MAX_SEATS,"exact 4096-seat registry bound commits last free slot");
        blocked(bounded,preview(bounded,request(900,2,1,0),chair(2)),"row_limit");
        SeatRegistry ceiling=SeatRegistry.decode("{\"schema\":2,\"revision\":9223372036854775806,\"seats\":[]}");String ceilingBefore=ceiling.encode();
        reject("row_stale",()->RowBatch.preview(ceiling,Long.MAX_VALUE-1,request(1,1,1,0),List.of(chair(1))));unchanged(ceiling,ceilingBefore,Long.MAX_VALUE-1);
    }
    private static void countDisplayIdentity(){
        SeatRegistry registry=new SeatRegistry();RowBatch.Preview letters=preview(registry,request(1,1,1,0,GuidePreset.RowScheme.LETTERS),chair(1),chair(2));RowBatch.commit(registry,letters,letters.candidates());
        SeatRegistry.Count a=registry.counts().stream().filter(c->c.row()==1).findFirst().orElseThrow();
        check(a.total()==2&&a.rowScheme()==GuidePreset.RowScheme.LETTERS&&a.rowLabel().equals("A"),"uniform letter rows retain their explicit scheme in count summaries");
        RowBatch.Preview aa=preview(registry,request(27,1,1,0,GuidePreset.RowScheme.LETTERS),chair(3));RowBatch.commit(registry,aa,aa.candidates());
        check(registry.counts().stream().anyMatch(c->c.row()==27&&c.rowLabel().equals("AA")),"multi-letter row counts use plain AA");
        SeatRegistry.Seat numeric=registry.put(registry.revision(),"",HALL,"lower",1,3,4,SeatRegistry.Status.MAINTENANCE,"minecraft:oak_stairs");
        String before=registry.encode();long revision=registry.revision();List<SeatRegistry.Count> counts=registry.counts();
        SeatRegistry.Count mixed=counts.stream().filter(c->c.row()==1).findFirst().orElseThrow();
        check(mixed.total()==3&&mixed.available()==2&&mixed.maintenance()==1&&mixed.rowScheme()==GuidePreset.RowScheme.NUMERIC&&mixed.rowLabel().equals("1"),"mixed display schemes share one numeric identity group and explicitly fall back to numeric display");
        check(counts.stream().filter(c->c.row()>0).count()==2&&counts.stream().anyMatch(c->c.zone().isEmpty()&&c.total()==4&&c.rowLabel().isEmpty()),"display schemes never split or renumber hall/zone/row counts");unchanged(registry,before,revision);
        registry.put(revision,numeric.id(),numeric.hall(),numeric.zone(),numeric.row(),numeric.number(),numeric.position(),numeric.status(),numeric.block(),numeric.seatUid(),GuidePreset.RowScheme.LETTERS);
        check(registry.counts().stream().anyMatch(c->c.row()==1&&c.total()==3&&c.rowLabel().equals("A")),"explicitly uniform display edits recover letter summary without migrating row identity");
        SeatRegistry.Count old=new SeatRegistry.Count(HALL,"Lower",27,1,1,0,0);
        check(old.rowScheme()==GuidePreset.RowScheme.NUMERIC&&old.rowLabel().equals("27"),"old count constructor remains numeric and source compatible");
        String legacy="{\"hall\":\""+HALL+"\",\"zone\":\"Lower\",\"row\":27,\"total\":1,\"available\":1,\"maintenance\":0,\"disabled\":0}";
        check(SeatRegistry.JSON.fromJson(legacy,SeatRegistry.Count.class).equals(old),"old count wire JSON receives the numeric fallback");
    }
    private static void physicalVariants(){
        SeatRegistry registry=new SeatRegistry();
        RowBatch.Candidate doubleSeat=RowBatch.Candidate.chair(701,UUID.fromString(uid(701)),"north",SeatRegistry.DOUBLE_SUPPORT_BLOCK);
        RowBatch.Preview plan=preview(registry,request(1,1,1,0,GuidePreset.RowScheme.LETTERS),chair(700),RowBatch.Candidate.gap(702),doubleSeat);
        check(plan.canCommit()&&plan.count()==2&&plan.gaps()==1,"mixed physical variants preserve one explicit aisle");
        RowBatch.Committed result=RowBatch.commit(registry,plan,plan.candidates());
        check(result.seats().get(0).block().equals(SeatRegistry.PHYSICAL_BLOCK)&&result.seats().get(1).block().equals(SeatRegistry.DOUBLE_SUPPORT_BLOCK),"row commit records actual variant, never masquerades as the default block");
        check(result.seats().get(1).number()==2&&result.seats().get(1).rowLabel().equals("A"),"variant shares numbering and plain letter row");
        check(SeatRegistry.decode(registry.encode()).seats().equals(registry.seats()),"variant identity and actual block survive serialization");
        SeatRegistry fresh=new SeatRegistry();RowBatch.Preview before=preview(fresh,request(2,1,1,0),doubleSeat);
        reject("row_stale_preview",()->RowBatch.commit(fresh,before,List.of(chair(701))));
        check(fresh.seats().isEmpty(),"variant change under same forged UID cannot commit stale preview");
        RowBatch.Candidate wrong=RowBatch.Candidate.chair(703,UUID.fromString(uid(703)),"north","minecraft:stone");
        hasError(preview(fresh,request(3,1,1,0),wrong),"row_wrong_block");
    }
    public static void run(){physicalVariants();serializationAndIdentity();numberingAndSkips();invalidAndAtomic();staleAndBounded();countDisplayIdentity();System.out.println("RowBatchChecks passed: "+checks+" checks");}
    public static void main(String[] args){run();}
}
