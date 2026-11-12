package local.cinemamanager.seats.fractional;

import java.util.*;
import java.util.function.Supplier;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

/** Registered shared collision transport; visible chairs retain the verified map's original item displays. */
public final class FractionalSeatBlock extends BaseEntityBlock {
    public static final BooleanProperty HOST_SLAB=BooleanProperty.create("host_slab");
    public static final EnumProperty<FractionalSeatHost.Material> HOST_MATERIAL=EnumProperty.create("host_material",FractionalSeatHost.Material.class);
    private static final VoxelShape SLAB=Block.box(0,0,0,16,8,16);
    private final Supplier<? extends BlockEntityType<?>> type;
    private final MapCodec<FractionalSeatBlock> codec;
    public FractionalSeatBlock(Properties p,Supplier<? extends BlockEntityType<?>> type){
        super(p.dynamicShape().noOcclusion().noLootTable().strength(-1f,3_600_000f).pushReaction(PushReaction.BLOCK)
            .isSuffocating((s,w,pos)->false).isViewBlocking((s,w,pos)->false));
        if(!hasCollision)throw new IllegalArgumentException("Collision must be enabled");
        this.type=Objects.requireNonNull(type);codec=simpleCodec(q->new FractionalSeatBlock(q,type));
        registerDefaultState(stateDefinition.any().setValue(HOST_SLAB,false).setValue(HOST_MATERIAL,FractionalSeatHost.Material.DEEPSLATE));
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return codec;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(HOST_SLAB,HOST_MATERIAL);}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new FractionalSeatTile(type.get(),pos,state);}
    @Override protected RenderShape getRenderShape(BlockState s){return s.getValue(HOST_SLAB)?RenderShape.MODEL:RenderShape.INVISIBLE;}
    public static VoxelShape hostShape(BlockState s){return s.hasProperty(HOST_SLAB)&&s.getValue(HOST_SLAB)?FractionalSeatHost.shape(FractionalSeatHost.slab(s.getValue(HOST_MATERIAL))):Shapes.empty();}
    @Override protected VoxelShape getCollisionShape(BlockState s,BlockGetter w,BlockPos p,CollisionContext context){return w.getBlockEntity(p) instanceof FractionalSeatTile tile?tile.collisionShape():hostShape(s);}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter w,BlockPos p,CollisionContext context){return getCollisionShape(s,w,p,context);}
    @Override protected VoxelShape getVisualShape(BlockState s,BlockGetter w,BlockPos p,CollisionContext c){return hostShape(s);}
    @Override protected VoxelShape getOcclusionShape(BlockState s,BlockGetter w,BlockPos p){return hostShape(s);}
    @Override protected int getLightBlock(BlockState s,BlockGetter w,BlockPos p){return 0;}
    @Override protected boolean propagatesSkylightDown(BlockState s,BlockGetter w,BlockPos p){return true;}
    @Override protected float getShadeBrightness(BlockState s,BlockGetter w,BlockPos p){return 1f;}
    @Override protected InteractionResult useWithoutItem(BlockState s,Level level,BlockPos p,Player actor,BlockHitResult hit){
        if(level.isClientSide)return InteractionResult.SUCCESS;
        return actor instanceof ServerPlayer player?FractionalSeatIntegration.use(player,hit):InteractionResult.FAIL;
    }
}
