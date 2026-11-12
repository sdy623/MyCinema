package local.cinemamanager.client;

import com.mojang.blaze3d.vertex.PoseStack;
import local.cinemamanager.CinemaManager;
import local.cinemamanager.CinemaDisplays;
import local.cinemamanager.projection.CinemaProjectorTile;
import me.srrapero720.waterframes.client.display.Display;
import me.srrapero720.waterframes.client.rendering.DisplayRenderer;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Renders a projector from its terminal's one shared display object.
 *
 * <p>Official WaterFrames reads the {@link DisplayTile#display} field directly.
 * A MyCinema projector deliberately owns no follower or decoder, so the field is
 * empty even though {@link CinemaProjectorTile#activeDisplay()} resolves the TMS
 * authority. Borrow that display only for this render call, then restore the
 * endpoint. Both projectors therefore draw the same decoded texture without
 * acquiring another player.</p>
 */
@EventBusSubscriber(modid=CinemaManager.ID,value=Dist.CLIENT)
public final class CinemaProjectionRenderer extends DisplayRenderer {
    public CinemaProjectionRenderer(BlockEntityRendererProvider.Context context){super(context);}
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void register(EntityRenderersEvent.RegisterRenderers event){
        event.registerBlockEntityRenderer(CinemaDisplays.TILE_CINEMA_PROJECTOR.get(),CinemaProjectionRenderer::new);
    }
    @Override public void render(DisplayTile tile,float partialTicks,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        Display previous=tile.display;
        Display shared=tile instanceof CinemaProjectorTile projector?projector.activeDisplay():previous;
        if(!tile.data.active)return;
        // A stopped TMS has no picture to project. WaterFrames keeps its last
        // display object alive for reuse; drawing that stopped texture produces
        // an opaque black cinema-sized plane which can hide the cabinet and TMS.
        if(shared==null||shared.player()==null||shared.player().stopped())return;
        tile.display=shared;
        CinemaProjectorTile projector=(CinemaProjectorTile)tile;
        pose.pushPose();
        pose.translate(projector.projectionOffsetX,projector.projectionOffsetY,projector.projectionOffsetZ);
        try{super.render(tile,partialTicks,pose,buffers,light,overlay);}
        finally{pose.popPose();tile.display=previous;}
    }
}
