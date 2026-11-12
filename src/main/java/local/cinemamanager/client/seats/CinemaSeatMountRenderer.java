package local.cinemamanager.client.seats;

import local.cinemamanager.CinemaManager;
import local.cinemamanager.seats.CinemaSeatMount;
import local.cinemamanager.seats.SeatsModule;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Registration is isolated to the physical client. The mount emits no geometry or shadow. */
@EventBusSubscriber(modid=CinemaManager.ID,value=Dist.CLIENT)
public final class CinemaSeatMountRenderer extends EntityRenderer<CinemaSeatMount> {
    public CinemaSeatMountRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius=0; }
    @Override public ResourceLocation getTextureLocation(CinemaSeatMount mount) { return CinemaManager.id("textures/block/cinema_seat_upholstery.png"); }
    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SeatsModule.CHAIR_MOUNT.get(),CinemaSeatMountRenderer::new);
    }
}
