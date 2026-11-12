package local.cinemamanager.qa;

import local.cinemamanager.CinemaDisplays;
import local.cinemamanager.projection.CinemaProjectorTile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Legacy model-core intent is persisted by the MyCinema device, not a private dependency property. */
@GameTestHolder("mycinema") @PrefixGameTestTemplate(false)
public final class ModelCoreGameTests {
    @GameTest(template="empty",timeoutTicks=40)
    public static void model_core_identity_and_nbt(GameTestHelper h) {
        BlockPos pos=new BlockPos(4,2,4);h.setBlock(pos,CinemaDisplays.CINEMA_PROJECTOR.get());
        CinemaProjectorTile tile=h.getBlockEntity(pos);var identity=tile.identity();h.assertFalse(tile.modelCore(),"new projector keeps visible-shell default");
        tile.modelCore(true);var saved=tile.saveWithFullMetadata(h.getLevel().registryAccess());
        var restored=(CinemaProjectorTile)BlockEntity.loadStatic(tile.getBlockPos(),tile.getBlockState(),saved,h.getLevel().registryAccess());
        h.assertTrue(restored!=null&&restored.modelCore()&&restored.identity().equals(identity),"model-core compatibility flag and identity survive NBT");h.succeed();
    }
}
