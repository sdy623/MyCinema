package local.cinemamanager.seats.fractional;

import com.google.gson.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Closed 34-cell finish contract. No clipping, inferred palette or arbitrary carpet hosts. */
final class Original368CarpetContract {
    static boolean worldScope(FractionalSeatLayoutVersion.Version version,net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        return version==FractionalSeatLayoutVersion.Version.ORIGINAL368_CARPET_V2&&net.minecraft.world.level.Level.OVERWORLD.equals(dimension);
    }
    static boolean blackPosition(BlockPos p) {
        return p.getY()==82&&p.getZ()==51&&(p.getX()==21||p.getX()==22||p.getX()==45||p.getX()==46);
    }
    static boolean grayPosition(BlockPos p) {
        return p.getY()==82&&p.getZ()==51&&p.getX()>=18&&p.getX()<=49&&!blackPosition(p)
            ||p.getY()==83&&p.getZ()==54&&(p.getX()==18||p.getX()==49);
    }
    static Set<BlockPos> positions() {
        Set<BlockPos> out=new HashSet<>();for(int x=18;x<=49;x++)out.add(new BlockPos(x,82,51));
        out.add(new BlockPos(18,83,54));out.add(new BlockPos(49,83,54));return Set.copyOf(out);
    }
    static void validate(FractionalSeatLayout layout,JsonObject root) {
        if(!layout.isCarpetV2()||!root.keySet().equals(Set.of("plan_sha256","finishes"))
            ||!Original368Resources.PLAN.equals(root.get("plan_sha256").getAsString()))throw bad("finish schema/plan");
        JsonArray entries=root.getAsJsonArray("finishes");if(entries==null||entries.size()!=34)throw bad("finish count");
        Set<BlockPos> seen=new HashSet<>();
        for(JsonElement value:entries){
            JsonObject e=value.getAsJsonObject();if(!e.keySet().equals(Set.of("pos","state","base_pos","base_state")))throw bad("finish fields");
            BlockPos p=pos(e,"pos"),base=pos(e,"base_pos");
            if(!positions().contains(p)||!seen.add(p)||!base.equals(p.below()))throw bad("finish position/base");
            BlockState state=FractionalSeatLayout.state(e.getAsJsonObject("state")),baseState=FractionalSeatLayout.state(e.getAsJsonObject("base_state"));
            BlockState required=(grayPosition(p)?Blocks.GRAY_CARPET:Blocks.BLACK_CARPET).defaultBlockState();
            if(!state.equals(required)||!layout.supportAt(p).filter(state::equals).isPresent()
                ||!layout.supportAt(base).filter(baseState::equals).isPresent())throw bad("finish/support material");
            if(grayPosition(p)){
                if(!layout.cells.containsKey(p)||!layout.expectedHost(p).equals(state))throw bad("gray carpet host");
                FractionalSeatHost.validateData(layout.cells.get(p),state);
            }else if(layout.cells.containsKey(p))throw bad("ordinary black carpet became proxy");
        }
        if(!seen.equals(positions()))throw bad("finish set");
        for(var entry:layout.cells.entrySet()){
            BlockState h=layout.expectedHost(entry.getKey());
            if(!FractionalSeatHost.allowedAt(h,entry.getKey())||h.is(Blocks.GRAY_CARPET)!=grayPosition(entry.getKey()))throw bad("carpet host outside whitelist/missing");
            if(h.is(Blocks.BLACK_CARPET))throw bad("black carpet cannot be a fractional host");
        }
    }
    static boolean baseMatches(BlockPos carpet,BlockState actual) {
        if(!grayPosition(carpet)||!actual.getFluidState().isEmpty())return false;
        return FractionalSeatLayout.get(FractionalSeatLayoutVersion.Version.ORIGINAL368_CARPET_V2).supportAt(carpet.below()).filter(actual::equals).isPresent();
    }
    static boolean liveBaseValid(net.minecraft.server.level.ServerLevel level,BlockPos carpet) {
        BlockPos base=carpet.below();
        return level.hasChunkAt(base)&&!level.isOutsideBuildHeight(base)&&level.getWorldBorder().isWithinBounds(base)
            &&level.getBlockEntity(base)==null&&baseMatches(carpet,level.getBlockState(base));
    }
    static boolean validOwners(BlockPos pos,FractionalSeatData data){
        if(!grayPosition(pos))return false;
        var expected=FractionalSeatLayout.get(FractionalSeatLayoutVersion.Version.ORIGINAL368_CARPET_V2).cells.get(pos);
        if(expected==null)return false;
        for(var owner:data.owners())if(!expected.owner(owner.uid()).filter(owner::equals).isPresent())return false;
        return true;
    }
    private static BlockPos pos(JsonObject e,String name){
        JsonArray a=e.getAsJsonArray(name);if(a==null||a.size()!=3)throw bad("position triple");int[] v=new int[3];
        for(int i=0;i<3;i++){if(!a.get(i).isJsonPrimitive()||!a.get(i).getAsJsonPrimitive().isNumber())throw bad("numeric position");double n=a.get(i).getAsDouble();if(!Double.isFinite(n)||n!=Math.rint(n)||n<Integer.MIN_VALUE||n>Integer.MAX_VALUE)throw bad("integer position");v[i]=(int)n;}
        return new BlockPos(v[0],v[1],v[2]);
    }
    private static IllegalArgumentException bad(String detail){return new IllegalArgumentException("Original368 carpet v2: "+detail);}
    private Original368CarpetContract(){}
}
