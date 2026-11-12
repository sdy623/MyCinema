package local.cinemamanager.seats;

import java.util.*;

/**
 * Pure, atomic row-numbering model. The server supplies an explicit ordered span of loaded-world
 * observations; this class never discovers chairs, infers aisles, or authorizes access to a world.
 * Only the server may classify CHAIR after checking the actual chair block, support and block entity.
 * Scope, owner, exact card, permissions, loaded chunks and physical observations must be checked again
 * immediately before commit on the server thread. Never accept a client-provided Preview as authority.
 */
public final class RowBatch {
    public static final int MAX_CELLS=SeatRegistry.MAX_SEATS;
    public enum Kind { CHAIR, GAP, BLOCKED, WRONG_BLOCK, UNLOADED }
    public record Request(String hall,String zone,int row,int start,int direction,int end,GuidePreset.RowScheme rowScheme) {
        public Request {
            try{hall=SeatRegistry.uuid(hall);zone=SeatRegistry.label(zone,32,false);}
            catch(IllegalArgumentException invalid){throw SeatRegistry.error("row_malformed");}
            if(row<1||row>SeatRegistry.MAX_NUMBER||start<1||start>SeatRegistry.MAX_NUMBER
                    ||(direction!=1&&direction!=-1)||end<0||end>SeatRegistry.MAX_NUMBER
                    ||end!=0&&(end-start)*direction<0)throw SeatRegistry.error("row_range");
            if(rowScheme==null)throw SeatRegistry.error("row_malformed");
        }
        public String rowLabel(){return SeatRegistry.rowLabel(row,rowScheme);}
        public int limit(){return end==0?(direction==1?SeatRegistry.MAX_NUMBER:1):end;}
    }
    /** GAP is an explicit aisle observation, never a fallback for a wrong block or obstruction. */
    public record Candidate(long position,Kind kind,String seatUid,String facing,String block) {
        public Candidate(long position,Kind kind,String seatUid,String facing) { this(position,kind,seatUid,facing,kind==Kind.CHAIR?SeatRegistry.PHYSICAL_BLOCK:""); }
        public Candidate {
            if(seatUid==null)seatUid="";
            if(facing==null)facing="";
            if(block==null)block="";
        }
        public static Candidate chair(long position,UUID seatUid,String facing){return chair(position,seatUid==null?"":seatUid.toString(),facing);}
        public static Candidate chair(long position,String seatUid,String facing){return new Candidate(position,Kind.CHAIR,seatUid,facing);}
        public static Candidate chair(long position,UUID seatUid,String facing,String block){return new Candidate(position,Kind.CHAIR,seatUid==null?"":seatUid.toString(),facing,block);}
        public static Candidate gap(long position){return new Candidate(position,Kind.GAP,"","");}
        public static Candidate blocked(long position){return new Candidate(position,Kind.BLOCKED,"","");}
        public static Candidate wrongBlock(long position){return new Candidate(position,Kind.WRONG_BLOCK,"","");}
        public static Candidate unloaded(long position){return new Candidate(position,Kind.UNLOADED,"","");}
    }
    public record Assignment(long position,String seatUid,int number) {}
    /** code is a stable mycinema.seats.* translation key; zero number means no proposed number. */
    public record Issue(String code,long position,int number,String detail) {}
    public record Preview(long revision,Request request,List<Candidate> candidates,List<Assignment> assignments,
                          List<Issue> warnings,List<Issue> errors,int gaps,int skipped) {
        public Preview {
            candidates=List.copyOf(candidates);assignments=List.copyOf(assignments);
            warnings=List.copyOf(warnings);errors=List.copyOf(errors);
        }
        public boolean canCommit(){return errors.isEmpty()&&!assignments.isEmpty();}
        public int count(){return assignments.size();}
    }
    public record Committed(long revision,List<SeatRegistry.Seat> seats,int gaps,int skipped) {
        public Committed { seats=List.copyOf(seats); }
        public int count(){return seats.size();}
    }

    /** Plain ASCII row labels only; display choice never changes the stored numeric identity. */
    public static int parseRowLabel(String text,GuidePreset.RowScheme scheme){
        if(text==null||scheme==null)throw SeatRegistry.error("row_range");
        String clean=text.strip();if(clean.isEmpty()||clean.length()>3)throw SeatRegistry.error("row_range");
        int row=0;
        for(char c:clean.toCharArray()){
            if(scheme==GuidePreset.RowScheme.NUMERIC){
                if(c<'0'||c>'9')throw SeatRegistry.error("row_range");row=row*10+c-'0';
            }else{
                // Check ASCII before case normalization: Unicode expansions such as ß -> SS are not labels.
                if(c>='a'&&c<='z')c=(char)(c-'a'+'A');
                if(c<'A'||c>'Z')throw SeatRegistry.error("row_range");row=row*26+c-'A'+1;
            }
        }
        if(row<1||row>SeatRegistry.MAX_NUMBER)throw SeatRegistry.error("row_range");return row;
    }

    public static Preview preview(SeatRegistry registry,long expectedRevision,Request request,List<Candidate> orderedCandidates) {
        if(registry==null||request==null||orderedCandidates==null)throw SeatRegistry.error("row_malformed");
        if(orderedCandidates.size()>MAX_CELLS)throw SeatRegistry.error("row_limit");
        if(orderedCandidates.isEmpty())throw SeatRegistry.error("row_empty");
        for(Candidate candidate:orderedCandidates)if(candidate==null)throw SeatRegistry.error("row_malformed");
        List<Candidate> candidates=List.copyOf(orderedCandidates);
        synchronized(registry){
            requireRevision(registry,expectedRevision);
            List<SeatRegistry.Seat> inventory=registry.seats();
            Map<Long,SeatRegistry.Seat> positions=new HashMap<>();
            Map<String,SeatRegistry.Seat> identities=new HashMap<>();
            Map<Integer,SeatRegistry.Seat> numbers=new HashMap<>();
            for(SeatRegistry.Seat seat:inventory){
                positions.put(seat.position(),seat);
                if(seat.verifiedIdentity())identities.put(seat.seatUid(),seat);
                if(seat.hall().equals(request.hall())&&seat.zone().equalsIgnoreCase(request.zone())&&seat.row()==request.row())numbers.put(seat.number(),seat);
            }
            List<Assignment> assignments=new ArrayList<>();List<Issue> warnings=new ArrayList<>(),errors=new ArrayList<>();
            Set<Long> observedPositions=new HashSet<>();Set<String> observedUids=new HashSet<>();
            int next=request.start(),gaps=0,skipped=0;String rowFacing=null;
            for(Candidate candidate:candidates){
                long position=candidate.position();
                if(!observedPositions.add(position)){errors.add(issue("row_duplicate_position",position,0,""));continue;}
                if(candidate.kind()==null){errors.add(issue("row_malformed",position,0,""));continue;}
                SeatRegistry.Seat indexed=positions.get(position);
                if(candidate.kind()!=Kind.CHAIR){
                    if(!candidate.seatUid().isEmpty()||!candidate.facing().isEmpty()||!candidate.block().isEmpty())errors.add(issue("row_malformed",position,0,""));
                    switch(candidate.kind()){
                        case GAP->{gaps++;if(indexed!=null)errors.add(issue("row_missing_seat",position,0,indexed.id()));}
                        case BLOCKED->errors.add(issue("row_blocked",position,0,""));
                        case WRONG_BLOCK->errors.add(issue("row_wrong_block",position,0,""));
                        case UNLOADED->errors.add(issue("row_unloaded",position,0,""));
                        default->throw SeatRegistry.error("row_malformed");
                    }
                    continue;
                }
                if(!SeatRegistry.physicalBlock(candidate.block()))errors.add(issue("row_wrong_block",position,0,candidate.block()));
                boolean validUid=true;
                try{SeatRegistry.uuid(candidate.seatUid());}
                catch(IllegalArgumentException invalid){errors.add(issue("row_invalid_uid",position,0,""));validUid=false;}
                if(validUid&&!observedUids.add(candidate.seatUid()))errors.add(issue("row_duplicate_uid",position,0,candidate.seatUid()));
                if(!Set.of("north","east","south","west").contains(candidate.facing()))errors.add(issue("row_invalid_facing",position,0,""));
                else if(rowFacing==null)rowFacing=candidate.facing();
                else if(!rowFacing.equals(candidate.facing()))errors.add(issue("row_mixed_facing",position,0,candidate.facing()));
                SeatRegistry.Seat sameUid=validUid?identities.get(candidate.seatUid()):null;
                if(sameUid!=null&&sameUid.position()!=position)errors.add(issue("row_uid_conflict",position,0,sameUid.id()));
                if(indexed!=null){
                    skipped++;
                    if(indexed.verifiedIdentity()&&!indexed.seatUid().equals(candidate.seatUid()))errors.add(issue("row_replaced_uid",position,0,indexed.id()));
                    warnings.add(issue(indexed.verifiedIdentity()?"row_existing_skipped":"row_legacy_unverified",position,indexed.number(),indexed.id()));
                    continue;
                }
                // A conflicting number is not silently skipped or renumbered: the entire row stays pending.
                if(next<1||next>SeatRegistry.MAX_NUMBER||(next-request.limit())*request.direction()>0){
                    errors.add(issue("row_range",position,next,""));
                }else{
                    SeatRegistry.Seat conflict=numbers.get(next);
                    if(conflict!=null)errors.add(issue("row_number_conflict",position,next,conflict.id()));
                    assignments.add(new Assignment(position,candidate.seatUid(),next));
                }
                next+=request.direction();
            }
            if(inventory.size()>SeatRegistry.MAX_SEATS-assignments.size())errors.add(issue("row_limit",0,0,""));
            if(assignments.isEmpty()&&errors.isEmpty())errors.add(issue("row_empty",0,0,""));
            return new Preview(expectedRevision,request,candidates,assignments,warnings,errors,gaps,skipped);
        }
    }

    /** Fresh observations must be sampled by the server under the same authorized scope. */
    public static Committed commit(SeatRegistry registry,Preview expected,List<Candidate> freshCandidates) {
        if(registry==null||expected==null)throw SeatRegistry.error("row_malformed");
        synchronized(registry){
            requireRevision(registry,expected.revision());
            if(!expected.canCommit())throw failure(expected);
            Preview fresh=preview(registry,expected.revision(),expected.request(),freshCandidates);
            if(!fresh.canCommit())throw failure(fresh);
            if(!expected.candidates().equals(fresh.candidates())){
                Map<Long,Candidate> current=new HashMap<>();for(Candidate candidate:fresh.candidates())current.put(candidate.position(),candidate);
                for(Candidate before:expected.candidates()){
                    Candidate after=current.get(before.position());
                    if(before.kind()==Kind.CHAIR&&after!=null&&after.kind()==Kind.CHAIR&&!before.seatUid().equals(after.seatUid()))throw SeatRegistry.error("row_replaced_uid");
                }
                throw SeatRegistry.error("row_stale_preview");
            }
            // Recompute every display/skip/conflict condition, not only the selected positions or count.
            if(!expected.equals(fresh))throw SeatRegistry.error("row_stale_preview");
            Request request=expected.request();List<SeatRegistry.Seat> additions=new ArrayList<>();
            Map<Long,String> blockTypes=new HashMap<>();for(Candidate candidate:fresh.candidates())blockTypes.put(candidate.position(),candidate.block());
            for(Assignment assignment:fresh.assignments())additions.add(new SeatRegistry.Seat(UUID.randomUUID().toString(),request.hall(),request.zone(),request.row(),assignment.number(),assignment.position(),
                    SeatRegistry.Status.AVAILABLE,blockTypes.get(assignment.position()),assignment.seatUid(),request.rowScheme()));
            List<SeatRegistry.Seat> saved=registry.insertBatch(expected.revision(),additions);
            return new Committed(registry.revision(),saved,fresh.gaps(),fresh.skipped());
        }
    }
    private static void requireRevision(SeatRegistry registry,long expected){
        try{registry.requireRevision(expected);}catch(IllegalArgumentException stale){throw SeatRegistry.error("row_stale");}
    }
    private static IllegalArgumentException failure(Preview preview){
        return preview.errors().isEmpty()?SeatRegistry.error("row_empty"):new IllegalArgumentException(preview.errors().getFirst().code());
    }
    private static Issue issue(String code,long position,int number,String detail){return new Issue("mycinema.seats."+code,position,number,detail);}
    private RowBatch(){}
}
