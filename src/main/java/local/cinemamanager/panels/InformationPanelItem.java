package local.cinemamanager.panels;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.function.Supplier;
/** Same inventory identity: wall-side placement uses the thin panel; floor keeps legacy behavior. */
public final class InformationPanelItem extends BlockItem {
    private final Supplier<? extends Block> wall;
    public InformationPanelItem(Block legacy,Supplier<? extends Block> wall,Properties p){super(legacy,p);this.wall=wall;}
    @Override public void registerBlocks(java.util.Map<Block,net.minecraft.world.item.Item> map,net.minecraft.world.item.Item item){super.registerBlocks(map,item);map.put(wall.get(),item);}
    @Override protected BlockState getPlacementState(BlockPlaceContext c){if(c.getClickedFace().getAxis().isHorizontal()){BlockState s=wall.get().getStateForPlacement(c);return s!=null&&canPlace(c,s)?s:null;}return super.getPlacementState(c);}
}
