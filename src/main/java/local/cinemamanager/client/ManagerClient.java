package local.cinemamanager.client;
import local.cinemamanager.*;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
@EventBusSubscriber(modid=CinemaManager.ID,value=Dist.CLIENT)
public final class ManagerClient {
    public static void snapshot(ManagerNetwork.Snapshot packet) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.screen instanceof ManagerScreen screen&&screen.source.equals(packet.source())&&screen.identity.equals(packet.identity()))screen.update(packet);
        else if(packet.open())mc.setScreen(new ManagerScreen(packet));
    }
    @SubscribeEvent public static void commands(net.neoforged.neoforge.client.event.RegisterClientCommandsEvent e){QaClientEvidence.commands(e);}
    @SubscribeEvent public static void qaTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e){ClientQaAutomation.tick();}
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers e){e.registerBlockEntityRenderer(CinemaManager.INFO_TILE.get(),InfoRenderer::new);}
}
