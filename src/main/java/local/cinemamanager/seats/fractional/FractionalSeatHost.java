package local.cinemamanager.seats.fractional;

import java.util.BitSet;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.shapes.*;

/** Finite exact dry host states. Layout manifests decide which particular state belongs at each cell. */
public final class FractionalSeatHost {
 public static final BlockState BOTTOM_SLAB=Blocks.POLISHED_DEEPSLATE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM).setValue(SlabBlock.WATERLOGGED,false);
 public static final BlockState ANDESITE_BOTTOM_SLAB=Blocks.POLISHED_ANDESITE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM).setValue(SlabBlock.WATERLOGGED,false);
 public enum Material implements StringRepresentable {
  DEEPSLATE("deepslate",0), ANDESITE("andesite",0),
  GRAY_WOOL_SLAB("gray_wool_slab",0), BLACK_WOOL_SLAB("black_wool_slab",0),
  GRAY_WOOL_STAIRS_SOUTH("gray_wool_stairs_south",1), BLACK_WOOL_STAIRS_SOUTH("black_wool_stairs_south",1), BLACK_WOOL_DEEPSLATE_STAIRS_SOUTH("black_wool_deepslate_stairs_south",1),
  GRAY_WOOL_HALF_DEPTH_SOUTH("gray_wool_half_depth_south",2), BLACK_WOOL_HALF_DEPTH_SOUTH("black_wool_half_depth_south",2), GRAY_CARPET("gray_carpet",3);
  private final String name;final int shape;Material(String n,int s){name=n;shape=s;}
  public String getSerializedName(){return name;}
 }
 private FractionalSeatHost(){}
 private static BlockState bottom(SlabBlock b){return b==null?null:b.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM).setValue(SlabBlock.WATERLOGGED,false);}
 private static BlockState south(StairBlock b){return b==null?null:b.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH).setValue(StairBlock.HALF,Half.BOTTOM).setValue(StairBlock.SHAPE,StairsShape.STRAIGHT).setValue(StairBlock.WATERLOGGED,false);}
 public static BlockState slab(Material m){
  if(m==null)throw new IllegalArgumentException("Missing host material");
  BlockState s=switch(m){
   case GRAY_CARPET->Blocks.GRAY_CARPET.defaultBlockState();
   case DEEPSLATE->BOTTOM_SLAB;case ANDESITE->ANDESITE_BOTTOM_SLAB;
   case GRAY_WOOL_SLAB->bottom(WoolSurfaceModule.GRAY_SLAB);case BLACK_WOOL_SLAB->bottom(WoolSurfaceModule.BLACK_SLAB);
   case GRAY_WOOL_STAIRS_SOUTH->south(WoolSurfaceModule.GRAY_STAIRS);case BLACK_WOOL_STAIRS_SOUTH->south(WoolSurfaceModule.BLACK_STAIRS);case BLACK_WOOL_DEEPSLATE_STAIRS_SOUTH->south(WoolSurfaceModule.BLACK_DEEPSLATE_STAIRS);
   case GRAY_WOOL_HALF_DEPTH_SOUTH->WoolSurfaceModule.GRAY_HALF_DEPTH==null?null:WoolSurfaceModule.GRAY_HALF_DEPTH.defaultBlockState().setValue(WoolHalfDepthBlock.FACING,Direction.SOUTH);
   case BLACK_WOOL_HALF_DEPTH_SOUTH->WoolSurfaceModule.BLACK_HALF_DEPTH==null?null:WoolSurfaceModule.BLACK_HALF_DEPTH.defaultBlockState().setValue(WoolHalfDepthBlock.FACING,Direction.SOUTH);
  };
  if(s==null)throw new IllegalArgumentException("Host surface registry unavailable");return s;
 }
 private static Material known(BlockState s){
  for(Material m:Material.values()){BlockState candidate;try{candidate=slab(m);}catch(IllegalArgumentException unavailable){continue;}if(candidate.equals(s))return m;}return null;
 }
 public static boolean allowed(BlockState s){return s!=null&&!s.hasBlockEntity()&&s.getFluidState().isEmpty()&&(s.is(Blocks.AIR)||s.is(Blocks.CAVE_AIR)||s.is(Blocks.VOID_AIR)||known(s)!=null);}
 public static boolean allowedAt(BlockState s,net.minecraft.core.BlockPos pos){return allowed(s)&&(!s.is(Blocks.GRAY_CARPET)||Original368CarpetContract.grayPosition(pos));}
 public static boolean allowedInWorld(BlockState s,net.minecraft.core.BlockPos pos,net.minecraft.server.level.ServerLevel level){
  if(!allowedAt(s,pos))return false;
  return !s.is(Blocks.GRAY_CARPET)||Original368CarpetContract.worldScope(FractionalSeatLayoutVersion.forLevel(level),level.dimension())&&Original368CarpetContract.liveBaseValid(level,pos);
 }
 public static boolean isSlab(BlockState s){Material m=known(s);return m!=null&&m.shape==0;}
 public static boolean hasSolidHost(BlockState s){return allowed(s)&&!s.isAir();}
 public static Material material(BlockState s){if(s.isAir())return Material.DEEPSLATE;Material m=known(s);if(m==null)throw new IllegalArgumentException("Unknown exact host state");return m;}
 public static boolean matchesCellState(BlockState host,BlockState cell){return allowed(host)&&cell.getBlock() instanceof FractionalSeatBlock&&cell.getValue(FractionalSeatBlock.HOST_SLAB)==hasSolidHost(host)&&cell.getValue(FractionalSeatBlock.HOST_MATERIAL)==material(host);}
 public static VoxelShape shape(BlockState host){
  if(host.isAir())return Shapes.empty();int kind=material(host).shape;
  if(kind==3)return Block.box(0,0,0,16,1,16);
  if(kind==0)return Block.box(0,0,0,16,8,16);
  if(kind==1)return Shapes.or(Block.box(0,0,0,16,8,16),Block.box(0,8,8,16,16,16));
  return Block.box(0,0,8,16,8,16);
 }
 public static BitSet hostVoxels(BlockState host){
  BitSet bits=new BitSet();if(host.isAir())return bits;int kind=material(host).shape;
  for(int x=0;x<64;x++)for(int y=0;y<(kind==3?4:kind==1?64:32);y++){int start=(x*64+y)*64;bits.set(start+((kind==2||y>=32)?32:0),start+64);}return bits;
 }
 public static void validateData(FractionalSeatData data,BlockState host){
  if(!allowed(host))throw new IllegalArgumentException("Unsupported exact host");
  if(hasSolidHost(host)){var bits=data.voxelsCopy();if(bits.cardinality()==FractionalSeatData.VOXELS/2)throw new IllegalArgumentException("Hosted full-cell wall prohibited");if(bits.intersects(hostVoxels(host)))throw new IllegalArgumentException("Chair penetrates retained host shape");}
 }
 public static BlockState read(CompoundTag tag){
  if(!(tag.get("fractional_host") instanceof CompoundTag saved))throw new IllegalArgumentException("Missing saved host");
  BlockState s=NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),saved);
  if(!allowed(s)||!saved.equals(NbtUtils.writeBlockState(s)))throw new IllegalArgumentException("Malformed host state");return s;
 }
 public static void write(CompoundTag tag,BlockState s){if(!allowed(s))throw new IllegalArgumentException("Unsupported host");tag.put("fractional_host",NbtUtils.writeBlockState(s));}
}
