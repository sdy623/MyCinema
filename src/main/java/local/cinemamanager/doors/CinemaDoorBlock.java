package local.cinemamanager.doors;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Original three-section door. No block entity, ticking entity, world installer, or dependency.
 * Neighbor callbacks see all sections updated. Client block-update packets remain separate.
 * Both finishes are manually operable, including metal emergency/egress doors.
 */
public final class CinemaDoorBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DoorHingeSide> HINGE = BlockStateProperties.DOOR_HINGE;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final BooleanProperty HALF_DROP = BooleanProperty.create("half_drop");
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    public static final EnumProperty<DoorAccess> ACCESS = EnumProperty.create("access", DoorAccess.class);
    public static final EnumProperty<DoorPart> PART = EnumProperty.create("part", DoorPart.class);
    private static final int BATCH_FLAGS = UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE;
    private static final ThreadLocal<Set<AssemblyKey>> REMOVING = ThreadLocal.withInitial(HashSet::new);
    private record AssemblyKey(Level level, BlockPos base) {}
    private final BlockSetType soundSet;
    private final MapCodec<CinemaDoorBlock> codec;

    public CinemaDoorBlock(Properties properties, BlockSetType soundSet) {
        super(properties.sound(soundSet.soundType()).dynamicShape().pushReaction(PushReaction.BLOCK)
                .isSuffocating((state, world, pos) -> false).isViewBlocking((state, world, pos) -> false));
        if (!hasCollision) throw new IllegalArgumentException("Cinema doors require collision-enabled properties");
        this.soundSet = soundSet;
        this.codec = simpleCodec(p -> new CinemaDoorBlock(p, soundSet));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)
                .setValue(HINGE, DoorHingeSide.LEFT).setValue(OPEN, false)
                .setValue(HALF_DROP, false).setValue(POWERED, false).setValue(PART, DoorPart.LOWER).setValue(ACCESS, DoorAccess.PUBLIC));
    }
    @Override protected MapCodec<? extends Block> codec() { return codec; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HINGE, OPEN, POWERED, PART, ACCESS, HALF_DROP);
    }
    public static BlockPos lowerPos(BlockPos pos, BlockState state) { return pos.below(state.getValue(PART).offset()); }
    private static boolean loaded(LevelReader level, BlockPos pos) { return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos); }
    private boolean member(BlockState state, DoorPart part) { return state.is(this) && state.getValue(PART) == part; }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return DoorGeometry.shape(state.getValue(FACING), state.getValue(HINGE), state.getValue(OPEN), state.getValue(PART), state.getValue(HALF_DROP));
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }
    @Override protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return DoorGeometry.shape(state.getValue(FACING), state.getValue(HINGE), state.getValue(OPEN), state.getValue(PART), state.getValue(HALF_DROP));
    }
    // Closed opaque panels block light at every metre, not only at the original 2m height.
    @Override protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) { return state.getValue(OPEN) ? 0 : 15; }
    @Override protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return state.getValue(OPEN); }
    @Override protected boolean useShapeForLightOcclusion(BlockState state) { return true; }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) { return type != PathComputationType.WATER && state.getValue(OPEN); }

    /** Only an exact full-footprint lower half slab may carry a lowered threshold.
     * Do not accept stairs, narrow platforms, water, or arbitrary incomplete supports. */
    public static boolean isHalfDropSupport(LevelReader level, BlockPos pos) {
        BlockState support = level.getBlockState(pos);
        return support.getFluidState().isEmpty()
                && !net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(
                        support.getCollisionShape(level, pos),
                        net.minecraft.world.phys.shapes.Shapes.box(0, 0, 0, 1, .5, 1),
                        net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME);
    }
    private static boolean supported(BlockState state, LevelReader level, BlockPos below) {
        return state.getValue(HALF_DROP) ? isHalfDropSupport(level, below)
                : level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /** Full-volume placement check is shared by the registered item and automatic placement. */
    public boolean hasPlacementRoom(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos base = context.getClickedPos();
        if (!loaded(level, base.below()) || !loaded(level, base.above(2))
                || !(level.getBlockState(base.below()).isFaceSturdy(level, base.below(), Direction.UP)
                     || isHalfDropSupport(level, base.below()))) return false;
        for (DoorPart part : DoorPart.values()) {
            BlockPos p = base.above(part.offset());
            if (!loaded(level, p) || !level.getBlockState(p).canBeReplaced(context)) return false;
        }
        return true;
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (!hasPlacementRoom(context)) return null;
        Direction facing = context.getHorizontalDirection();
        DoorHingeSide hinge = placementHinge(context, facing);
        BlockState result = defaultBlockState().setValue(FACING, facing).setValue(HINGE, hinge)
                .setValue(HALF_DROP, isHalfDropSupport(context.getLevel(), context.getClickedPos().below()));
        Boolean signal = sampledPower(context.getLevel(), context.getClickedPos(), null);
        if (Boolean.TRUE.equals(signal)) result = result.setValue(OPEN, true).setValue(POWERED, true);
        return result;
    }
    private DoorHingeSide placementHinge(BlockPlaceContext context, Direction facing) {
        Level level = context.getLevel(); BlockPos base = context.getClickedPos();
        Direction left = facing.getCounterClockWise(), right = facing.getClockWise();
        BlockPos l = base.relative(left), r = base.relative(right);
        if (loaded(level, l)) {
            BlockState s = level.getBlockState(l);
            if (availableForPair(level, l, s, facing, isHalfDropSupport(level, base.below()))) return DoorHingeSide.RIGHT;
        }
        if (loaded(level, r)) {
            BlockState s = level.getBlockState(r);
            if (availableForPair(level, r, s, facing, isHalfDropSupport(level, base.below()))) return DoorHingeSide.LEFT;
        }
        int leftSolid = 0, rightSolid = 0;
        for (int y = 0; y < 3; y++) {
            if (loaded(level, l.above(y)) && level.getBlockState(l.above(y)).isCollisionShapeFullBlock(level, l.above(y))) leftSolid++;
            if (loaded(level, r.above(y)) && level.getBlockState(r.above(y)).isCollisionShapeFullBlock(level, r.above(y))) rightSolid++;
        }
        if (leftSolid != rightSolid) return leftSolid > rightSolid ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
        double x = context.getClickLocation().x - base.getX() - .5;
        double z = context.getClickLocation().z - base.getZ() - .5;
        return x * right.getStepX() + z * right.getStepZ() > 0 ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity entity, ItemStack stack) {
        if (level.isClientSide) return;
        // The item validates all three spaces before any state is changed. The check below
        // also protects direct callers from overwriting an unexpected upper obstruction.
        for (int y = 1; y < 3; y++) {
            BlockPos p = pos.above(y);
            if (!loaded(level, p) || !level.getBlockState(p).canBeReplaced()) {
                level.destroyBlock(pos, true); return;
            }
        }
        for (DoorPart part : DoorPart.values()) if (part != DoorPart.LOWER)
            level.setBlock(pos.above(part.offset()), state.setValue(PART, part), BATCH_FLAGS);
        alignUnpairedNeighbor(level, pos);
        reconcile(level, pos);
        notifyAssembly(level, pos);
        level.scheduleTick(pos, this, 1);
    }
    private boolean availableForPair(Level level, BlockPos pos, BlockState state, Direction facing, boolean halfDrop) {
        if (!state.is(this) || state.getValue(HALF_DROP) != halfDrop) return false;
        if (!member(state, DoorPart.LOWER) || state.getValue(FACING) != facing || !isComplete(level, pos)) return false;
        BlockPos potential = pos.relative(DoorGeometry.partnerDirection(facing, state.getValue(HINGE)));
        return loaded(level, potential) && partner(level, pos) == null;
    }
    /** A newly placed second leaf can correct an unpaired first leaf, never an existing pair. */
    private void alignUnpairedNeighbor(Level level, BlockPos base) {
        BlockState state = level.getBlockState(base);
        BlockPos other = base.relative(DoorGeometry.partnerDirection(state.getValue(FACING), state.getValue(HINGE)));
        if (!loaded(level, other)) return;
        BlockState previous = level.getBlockState(other);
        if (!availableForPair(level, other, previous, state.getValue(FACING), state.getValue(HALF_DROP))) return;
        DoorHingeSide wanted = state.getValue(HINGE) == DoorHingeSide.LEFT ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
        if (previous.getValue(HINGE) == wanted) return;
        for (DoorPart part : DoorPart.values())
            level.setBlock(other.above(part.offset()), previous.setValue(PART, part).setValue(HINGE, wanted)
                    .setValue(ACCESS, level.getBlockState(other.above(part.offset())).getValue(ACCESS)), BATCH_FLAGS);
        notifyAssembly(level, other);
    }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos base = lowerPos(pos, state);
        if (!loaded(level, base.below())) return false;
        if (!supported(state, level, base.below())) return false;
        if (state.getValue(PART) == DoorPart.LOWER) return true;
        return loaded(level, base) && member(level.getBlockState(base), DoorPart.LOWER);
    }
    /** Structural completeness is checked separately from mutable OPEN/POWERED consistency. */
    public boolean isComplete(LevelReader level, BlockPos base) {
        if (!loaded(level, base) || !loaded(level, base.above(2))) return false;
        BlockState lower = level.getBlockState(base);
        if (!member(lower, DoorPart.LOWER) || !canSurvive(lower, level, base)) return false;
        for (DoorPart part : DoorPart.values()) {
            BlockState s = level.getBlockState(base.above(part.offset()));
            if (!member(s, part) || s.getValue(FACING) != lower.getValue(FACING) || s.getValue(HINGE) != lower.getValue(HINGE) || s.getValue(HALF_DROP) != lower.getValue(HALF_DROP)) return false;
        }
        return true;
    }
    private BlockPos partner(LevelReader level, BlockPos base) {
        BlockState state = level.getBlockState(base);
        BlockPos other = base.relative(DoorGeometry.partnerDirection(state.getValue(FACING), state.getValue(HINGE)));
        if (!loaded(level, other)) return null;
        BlockState s = level.getBlockState(other);
        return member(s, DoorPart.LOWER)
                && s.getValue(HALF_DROP) == state.getValue(HALF_DROP)
                && DoorGeometry.compatible(state.getValue(FACING), state.getValue(HINGE), s.getValue(FACING), s.getValue(HINGE))
                && isComplete(level, other) ? other : null;
    }
    /** Avoid hasNeighborSignal's indirect neighbor reads until their entire radius is loaded. */
    private Boolean sampledPower(Level level, BlockPos base, BlockPos other) {
        for (BlockPos p : other == null ? List.of(base) : List.of(base, other))
            if (!level.hasChunksAt(p.offset(-2, 0, -2), p.offset(2, 2, 2))) return null;
        for (BlockPos p : other == null ? List.of(base) : List.of(base, other))
            for (int y = 0; y < 3; y++) if (level.hasNeighborSignal(p.above(y))) return true;
        return false;
    }
    /** Public for scene integration and GameTest; returns false for an incomplete assembly. */
    public boolean setOpen(Entity actor, Level level, BlockPos anySection, boolean open) {
        if (level.isClientSide || !loaded(level, anySection)) return false;
        BlockState hit = level.getBlockState(anySection);
        if (!hit.is(this)) return false;
        BlockPos base = lowerPos(anySection, hit);
        if (!isComplete(level, base)) return false;
        // Reader-bound doors always permit inside exit, including a stale or unloaded controller.
        // The access bridge validates the registered inside side without loading a missing partner.
        if (actor instanceof Player player && open && local.cinemamanager.access.AccessDoors.egress(player, level, base)) return true;
        if (actor instanceof Player player && (!canUseManually(level, base, player)
                || (!open && hasControlledEntry(level, base)))) return false;
        BlockPos other = partner(level, base);
        BlockState lower = level.getBlockState(base);
        Boolean signal = sampledPower(level, base, other);
        boolean powered = signal == null ? lower.getValue(POWERED) : signal;
        // Map-owned card/egress controllers must also own the close cycle. A free-standing
        // manual opening outside their occupancy trigger would otherwise stay open forever.
        if (actor instanceof Player && hasControlledEntry(level, base) && !Boolean.TRUE.equals(signal)) return false;
        boolean changed = applyGroup(level, base, other, open, powered);
        if (changed) playChange(actor, level, base, open);
        return true;
    }
    private boolean hasControlledEntry(Level level, BlockPos base) {
        BlockPos other = partner(level, base);
        for (BlockPos p : other == null ? List.of(base) : List.of(base, other))
            for (DoorPart part : DoorPart.values())
                if (level.getBlockState(p.above(part.offset())).getValue(ACCESS) != DoorAccess.PUBLIC) return true;
        return false;
    }
    /**
     * Server-derived side check; neither the clicked face nor an item packet grants entry.
     * Both leaves are checked so a public/mismatched partner cannot open a protected leaf.
     * Null/non-player setOpen callers are trusted server scene/card integrations, like redstone.
     */
    public boolean canUseManually(Level level, BlockPos anySection, Player player) {
        if (player == null || player.level() != level || !loaded(level, anySection)) return false;
        BlockState hit = level.getBlockState(anySection);
        if (!hit.is(this)) return false;
        BlockPos base = lowerPos(anySection, hit);
        if (!isComplete(level, base)) return false;
        BlockState baseState = level.getBlockState(base);
        // An unavailable reciprocal cell may contain a controlled partner. Do not assume public.
        if (!loaded(level, base.relative(DoorGeometry.partnerDirection(baseState.getValue(FACING), baseState.getValue(HINGE))))) return false;
        BlockPos other = partner(level, base);
        for (BlockPos p : other == null ? List.of(base) : List.of(base, other)) {
            BlockState lower = level.getBlockState(p);
            for (DoorPart part : DoorPart.values()) {
                BlockState section = level.getBlockState(p.above(part.offset()));
                if (!section.getValue(ACCESS).allows(lower.getValue(FACING), p, player.position())) return false;
            }
        }
        return true;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            BlockPos base = lowerPos(pos, state);
            if (isComplete(level, base)) {
                if (local.cinemamanager.access.EmergencyAccess.keepOpen(player, level, base)) return InteractionResult.SUCCESS;
                // The manager's existing tablet may stay anywhere in carried inventory.
                // This is a click path, never a proximity tick or a blanket item-type bypass.
                if (player instanceof net.minecraft.server.level.ServerPlayer actor
                        && local.cinemamanager.access.TabletAccess.click(actor, pos)) return InteractionResult.SUCCESS;
                if (!setOpen(player, level, pos, hasControlledEntry(level, base) || !level.getBlockState(base).getValue(OPEN)))
                    player.displayClientMessage(Component.translatable("message.mycinema.controlled_entry"), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    private void playChange(Entity actor, Level level, BlockPos base, boolean open) {
        level.playSound(null, base, open ? soundSet.doorOpen() : soundSet.doorClose(), SoundSource.BLOCKS, 1, .9f + level.random.nextFloat() * .1f);
        level.gameEvent(actor, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, base);
    }
    /** Re-evaluate one loaded assembly without creating an opening lease or granting entry. */
    public void reconcileAccess(Level level, BlockPos base) { if (!level.isClientSide && loaded(level,base)) reconcile(level,base); }
    private void reconcile(Level level, BlockPos base) {
        if (!isComplete(level, base)) return;
        BlockPos other = partner(level, base);
        // LEFT always wins a damaged/mismatched pair's steady-state OPEN value.
        if (other != null && level.getBlockState(base).getValue(HINGE) == DoorHingeSide.RIGHT) {
            BlockPos swap = base; base = other; other = swap;
        }
        BlockState lower = level.getBlockState(base);
        Boolean signal = sampledPower(level, base, other);
        boolean power = signal == null ? lower.getValue(POWERED) : signal;
        boolean open = signal == null ? lower.getValue(OPEN) : DoorGeometry.reconciledOpen(power,
                lower.getValue(POWERED), lower.getValue(OPEN), other == null ? null : level.getBlockState(other).getValue(POWERED));
        open = local.cinemamanager.access.AccessDoors.reconciledOpen(level, base, other, power, open);
        if (applyGroup(level, base, other, open, power)) playChange(null, level, base, open);
    }
    private boolean applyGroup(Level level, BlockPos base, BlockPos other, boolean open, boolean powered) {
        List<BlockPos> changed = new ArrayList<>(6); boolean moved = false;
        for (BlockPos p : other == null ? List.of(base) : List.of(base, other)) {
            BlockState lower = level.getBlockState(p);
            boolean leafOpen = open || local.cinemamanager.access.EmergencyAccess.heldOpen(level,p);
            for (DoorPart part : DoorPart.values()) {
                BlockPos section = p.above(part.offset()); BlockState old = level.getBlockState(section);
                // Keep each persisted access mark: a partially edited PUBLIC lower section must
                // never erase a protected upper/middle segment and silently grant entry.
                BlockState next = lower.setValue(PART, part).setValue(OPEN, leafOpen).setValue(POWERED, powered)
                        .setValue(ACCESS, old.getValue(ACCESS));
                if (old != next) {
                    moved |= old.getValue(OPEN) != leafOpen;
                    level.setBlock(section, next, BATCH_FLAGS);
                    changed.add(section);
                }
            }
        }
        for (BlockPos p : changed) notifyLoadedNeighbors(level, p);
        return moved;
    }
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moving) {
        if (!level.isClientSide && !oldState.is(this)) level.scheduleTick(pos, this, 1);
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!level.isClientSide()) level.scheduleTick(pos, this, 1);
        return state;
    }
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos from, boolean moving) {
        if (!level.isClientSide) {
            BlockPos base = lowerPos(pos, state);
            // Immediate redstone edges are not delayed by the lower section's recovery tick.
            // All writes precede notifications; a nested callback sees an already-stable group.
            if (isComplete(level, base)) reconcile(level, base);
            else if (!canSurvive(state, level, pos)) level.destroyBlock(pos, true);
            else level.scheduleTick(pos, this, 1);
        }
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BlockPos base = lowerPos(pos, state);
        if (!isComplete(level, base)) {
            // All three members share one chunk. Never destroy because an adjacent chunk unloaded.
            level.destroyBlock(pos, true); return;
        }
        reconcile(level, base);
        // Persisted scheduled tick provides restart/chunk-boundary recovery without force loading.
        if (pos.equals(base)) level.scheduleTick(base, this, 20);
        else level.scheduleTick(base, this, 1);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!level.isClientSide && !replacement.is(this)) {
            BlockPos base = lowerPos(pos, state); AssemblyKey key = new AssemblyKey(level, base);
            local.cinemamanager.access.AccessDoors.removed(level, base);
            Set<AssemblyKey> removing = REMOVING.get();
            if (removing.add(key)) {
                try {
                    for (DoorPart part : DoorPart.values()) {
                        BlockPos p = base.above(part.offset());
                        if (!p.equals(pos) && loaded(level, p) && member(level.getBlockState(p), part))
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), BATCH_FLAGS | UPDATE_SUPPRESS_DROPS);
                    }
                    notifyAssembly(level, base);
                } finally { removing.remove(key); if (removing.isEmpty()) REMOVING.remove(); }
            }
        }
        super.onRemove(state, level, pos, replacement, moving);
    }
    private void notifyAssembly(Level level, BlockPos base) { for (int y = 0; y < 3; y++) notifyLoadedNeighbors(level, base.above(y)); }
    private void notifyLoadedNeighbors(Level level, BlockPos changed) {
        if (!loaded(level, changed)) return;
        BlockState state = level.getBlockState(changed);
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = changed.relative(direction);
            if (loaded(level, neighbor)) {
                level.neighborChanged(neighbor, this, changed);
                level.neighborShapeChanged(direction.getOpposite(), state, neighbor, changed, BATCH_FLAGS, 8);
            }
        }
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) {
        return mirror == Mirror.NONE ? state : rotate(state, mirror.getRotation(state.getValue(FACING))).cycle(HINGE);
    }
}
