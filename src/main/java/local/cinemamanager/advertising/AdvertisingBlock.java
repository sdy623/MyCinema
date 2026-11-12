package local.cinemamanager.advertising;

import com.mojang.serialization.MapCodec;
import local.cinemamanager.*;
import local.cinemamanager.panels.PanelScope;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

/** Identity anchor. The renderer and exact shape use eighth-unit geometry, not a full-block box. */
public final class AdvertisingBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING=InfoBlock.FACING;
    public static final BooleanProperty FLOOR=BooleanProperty.create("floor");
    public static final MapCodec<AdvertisingBlock> CODEC=simpleCodec(p->new AdvertisingBlock(p,false));
    private final boolean led;
    public AdvertisingBlock(Properties p,boolean led){super(p);this.led=led;registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH).setValue(FLOOR,false));}
    public boolean emissive(){return led;}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState>b){b.add(FACING,FLOOR);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){
        Direction clicked=c.getClickedFace();BlockPos pos=c.getClickedPos();Level level=c.getLevel();
        if(level.isOutsideBuildHeight(pos)||!level.hasChunkAt(pos)||!level.getWorldBorder().isWithinBounds(pos))return null;
        BlockState state=null;
        if(clicked.getAxis().isVertical()&&!c.replacingClickedOnBlock()){
            BlockPos source=pos.relative(clicked.getOpposite());
            if(!level.hasChunkAt(source))return null;
            BlockState sourceState=level.getBlockState(source);
            if(sourceState.getBlock() instanceof AdvertisingBlock||sourceState.is(CinemaManager.AD_PART.get())){
                // CHECK lookup must not synthesize a replacement identity for a missing parent tile.
                var tile=level.getChunkAt(source).getBlockEntity(source);
                InfoTile root=null;
                if(sourceState.is(this)&&tile instanceof InfoTile info)root=info;
                else if(sourceState.is(CinemaManager.AD_PART.get())&&tile instanceof AdvertisingPartTile part
                        &&part.anchor!=null&&level.hasChunkAt(part.anchor)
                        &&level.getChunkAt(part.anchor).getBlockEntity(part.anchor) instanceof InfoTile)root=part.root();
                // Borrow a live orientation; the normal item path still creates an independent owned unit.
                if(root==null||root.isRemoved()||root.getLevel()!=level||!level.hasChunkAt(root.getBlockPos())
                        ||level.getBlockEntity(root.getBlockPos())!=root||!root.getBlockState().is(this))return null;
                Player actor=c.getPlayer();
                boolean floor=root.getBlockState().getValue(FLOOR);
                // Standing boards carry only their owner's matching units, upwards from an actual top edge.
                if(floor&&(clicked!=Direction.UP||actor==null||!level.isClientSide&&!actor.getUUID().equals(root.owner)))return null;
                if(actor!=null&&(!level.mayInteract(actor,pos)||!actor.mayUseItemAt(pos,clicked,c.getItemInHand())))return null;
                state=defaultBlockState().setValue(FACING,root.getBlockState().getValue(FACING)).setValue(FLOOR,floor);
            }
        }
        if(state==null){
            if(clicked==Direction.DOWN)return null;
            state=defaultBlockState().setValue(FLOOR,clicked==Direction.UP).setValue(FACING,clicked==Direction.UP?c.getHorizontalDirection().getOpposite():clicked);
        }
        Player placer=c.getPlayer();
        return AdvertisingLifecycle.supportedForPlacement(level,pos,state,AdvertisingGeometry.DEFAULT,placer==null?null:placer.getUUID())?state:null;
    }
    @Override protected boolean canSurvive(BlockState s,LevelReader l,BlockPos p){AdvertisingGeometry geometry=l.getBlockEntity(p) instanceof InfoTile t?t.advertising:AdvertisingGeometry.DEFAULT;return AdvertisingLifecycle.supported(l,p,s,geometry,false);}
    @Override protected BlockState updateShape(BlockState s,Direction d,BlockState other,LevelAccessor l,BlockPos p,BlockPos otherPos){if(l instanceof Level level&&AdvertisingLifecycle.mutating(level,p))return s;if(!canSurvive(s,l,p))return Blocks.AIR.defaultBlockState();return super.updateShape(s,d,other,l,p,otherPos);}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){InfoTile t=l.getBlockEntity(p) instanceof InfoTile tile?tile:null;AdvertisingGeometry g=t==null?AdvertisingGeometry.DEFAULT:t.advertising;AdvertisingShape shape=t==null?AdvertisingShape.RECTANGLE:t.advertisingShape;return shape.shape(g,p,s.getValue(FACING),s.getValue(FLOOR),p);}
    @Override protected VoxelShape getCollisionShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return getShape(s,l,p,c);}
    @Override protected java.util.List<ItemStack> getDrops(BlockState state,net.minecraft.world.level.storage.loot.LootParams.Builder builder){
        var drops=super.getDrops(state,builder);
        var tile=builder.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY);
        if(!(tile instanceof InfoTile root)||root.assembledUnits<=1)return drops;
        var result=new java.util.ArrayList<ItemStack>();
        for(ItemStack stack:drops){int count=stack.is(this.asItem())?Math.min(256,root.assembledUnits)*stack.getCount():stack.getCount();while(count>0){var item=stack.copy();int amount=Math.min(count,item.getMaxStackSize());item.setCount(amount);result.add(item);count-=amount;}}
        return result;
    }
    @Override protected RenderShape getRenderShape(BlockState s){return RenderShape.INVISIBLE;}
    @Override public BlockEntity newBlockEntity(BlockPos p,BlockState s){return new InfoTile(p,s);}
    @Override public void setPlacedBy(Level l,BlockPos p,BlockState s,LivingEntity actor,ItemStack stack){if(!l.isClientSide&&actor!=null&&l.getBlockEntity(p) instanceof InfoTile t){t.initializePlacement(actor.getUUID());t.scope=PanelScope.MANUAL;t.advertising=AdvertisingGeometry.DEFAULT;t.advertisingShape=AdvertisingShape.RECTANGLE;t.dirty();}}
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos p,Player actor,BlockHitResult hit){if(!l.isClientSide&&actor instanceof ServerPlayer sp&&l.getBlockEntity(p) instanceof InfoTile t)t.interact(sp,true);return InteractionResult.sidedSuccess(l.isClientSide);}
    @Override protected void onRemove(BlockState old,Level l,BlockPos p,BlockState next,boolean moving){if(old.getBlock()!=next.getBlock()&&!l.isClientSide&&l.getBlockEntity(p) instanceof InfoTile t)AdvertisingLifecycle.removeParts(t);super.onRemove(old,l,p,next,moving);}
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l,BlockState s,BlockEntityType<T> type){return l.isClientSide?null:createTickerHelper(type,CinemaManager.INFO_TILE.get(),(a,b,c,t)->{if(a.getGameTime()%20==0)AdvertisingLifecycle.validateLoaded(t);if(!t.isRemoved())t.tick();});}
}
