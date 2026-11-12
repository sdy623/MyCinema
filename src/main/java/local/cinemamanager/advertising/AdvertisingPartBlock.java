package local.cinemamanager.advertising;

import com.mojang.serialization.MapCodec;
import local.cinemamanager.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

/** Invisible occupied cell. Its shape is only the local portion of the actual sign/foot. */
public final class AdvertisingPartBlock extends BaseEntityBlock {
    public static final MapCodec<AdvertisingPartBlock> CODEC=simpleCodec(AdvertisingPartBlock::new);
    public AdvertisingPartBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(AdvertisingBlock.FACING,Direction.NORTH).setValue(AdvertisingBlock.FLOOR,false));}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState>b){b.add(AdvertisingBlock.FACING,AdvertisingBlock.FLOOR);}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){if(l.getBlockEntity(p) instanceof AdvertisingPartTile t&&t.anchor!=null)return t.shape.shape(t.geometry,t.anchor,s.getValue(AdvertisingBlock.FACING),s.getValue(AdvertisingBlock.FLOOR),p);return Shapes.empty();}
    @Override protected VoxelShape getCollisionShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return getShape(s,l,p,c);}
    @Override protected RenderShape getRenderShape(BlockState s){return RenderShape.INVISIBLE;}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new AdvertisingPartTile(p,s);}
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player actor,BlockHitResult hit){if(!l.isClientSide&&actor instanceof ServerPlayer sp&&l.getBlockEntity(p) instanceof AdvertisingPartTile part){InfoTile root=part.root();if(root!=null&&actor.distanceToSqr(p.getCenter())<=64)root.interact(sp,true);}return InteractionResult.sidedSuccess(l.isClientSide);}
    @Override protected void onRemove(BlockState old,Level l,BlockPos p,BlockState next,boolean moving){if(old.getBlock()!=next.getBlock()&&!l.isClientSide&&l.getBlockEntity(p) instanceof AdvertisingPartTile part&&!AdvertisingLifecycle.mutating(l,part.anchor)){InfoTile root=part.root();if(root!=null)l.destroyBlock(root.getBlockPos(),true);}super.onRemove(old,l,p,next,moving);}
    @Override protected BlockState updateShape(BlockState s,Direction d,BlockState other,LevelAccessor l,BlockPos p,BlockPos otherPos){
        if(l.getBlockEntity(p) instanceof AdvertisingPartTile part){
            if(l instanceof Level level&&AdvertisingLifecycle.mutating(level,part.anchor))return s;
            if(part.anchor!=null&&!l.hasChunkAt(part.anchor))return s;
            InfoTile root=part.root();
            if(root==null||!AdvertisingLifecycle.supported(l,root.getBlockPos(),root.getBlockState(),root.advertising,false))return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(s,d,other,l,p,otherPos);
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l,BlockState s,BlockEntityType<T> type){return l.isClientSide?null:createTickerHelper(type,CinemaManager.AD_PART_TILE.get(),(a,b,c,t)->{if(Math.floorMod(a.getGameTime()+b.asLong(),20)==0)t.reconcile();});}
}
