package local.cinemamanager.seats.fractional;
import net.minecraft.SharedConstants;import net.minecraft.server.Bootstrap;import net.minecraft.core.BlockPos;import net.minecraft.nbt.CompoundTag;import net.minecraft.world.level.block.Blocks;
public final class HiddenPortsProfileChecks {
 static int n;static void check(boolean b){if(!b)throw new AssertionError("check "+n);n++;}
 static CompoundTag tag(String profile,String layout){var t=new CompoundTag();t.putInt("schema_version",1);t.putString("profile",profile);t.putString("layout",layout);return t;}
 static void refuses(CompoundTag t){try{FractionalSeatLayoutVersion.parse(t);throw new AssertionError("badpair accepted");}catch(IllegalArgumentException ok){n++;}}
 public static void main(String[]args){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();var oldV=FractionalSeatLayoutVersion.Version.Y_SMOOTH_V1;var newV=FractionalSeatLayoutVersion.Version.Y_SMOOTH_HIDDEN_PORTS_V1;
  check(FractionalSeatLayoutVersion.parse(tag("wool_smooth_y_v3",oldV.id))==oldV);check(FractionalSeatLayoutVersion.parse(tag("wool_smooth_y_hidden_ports_v4",newV.id))==newV);
  refuses(tag("wool_smooth_y_v3",newV.id));refuses(tag("wool_smooth_y_hidden_ports_v4",oldV.id));refuses(tag("wool_surfaces_v2",newV.id));
  var first=FractionalSeatLayout.get(args.length>0&&args[0].equals("newfirst")?newV:oldV);var second=FractionalSeatLayout.get(args.length>0&&args[0].equals("newfirst")?oldV:newV);check(first!=second);
  var old=FractionalSeatLayout.get(oldV);var fresh=FractionalSeatLayout.get(newV);check(old!=fresh);check(!old.usesHiddenPorts());check(fresh.usesHiddenPorts());check(old.isSmoothed()&&fresh.isSmoothed());check(old.cells.equals(fresh.cells));check(old.seats.keySet().equals(fresh.seats.keySet()));
  for(var uid:old.seats.keySet())check(old.seats.get(uid).anchor().equals(fresh.seats.get(uid).anchor()));
  for(int x:new int[]{15,52})for(var yz:new int[][]{{74,35},{80,49}}){var p=new BlockPos(x,yz[0],yz[1]);check(old.supportAt(p).orElseThrow().equals(Blocks.BLACK_WOOL.defaultBlockState()));check(fresh.supportAt(p).orElseThrow().equals(Blocks.GRAY_WOOL.defaultBlockState()));}
  for(int i=0;i<4;i++){check(old==FractionalSeatLayout.get(oldV));check(fresh==FractionalSeatLayout.get(newV));}
  var saved=new CompoundTag();saved.putString("layout",oldV.id);check(FractionalSeatIntegration.savedLayoutMatches(saved,oldV));check(!FractionalSeatIntegration.savedLayoutMatches(saved,newV));saved.putString("layout",newV.id);check(!FractionalSeatIntegration.savedLayoutMatches(saved,oldV));check(FractionalSeatIntegration.savedLayoutMatches(saved,newV));
  System.out.println("PASS "+n+" hidden-port version/cache/session checks order="+(args.length>0?args[0]:"oldfirst"));
 }
}
