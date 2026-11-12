package local.cinemamanager.client;

import local.cinemamanager.CinemaManager;
import local.cinemamanager.ManualItem;
import local.cinemamanager.ponder.MyCinemaPonderPlugin;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** The only bridge from common item registration to the client screen. */
@EventBusSubscriber(modid = CinemaManager.ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ManualClient {
    private ManualClient() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            PonderIndex.addPlugin(new MyCinemaPonderPlugin());
            ManualItem.installClientOpener(ManualScreen::open);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(PosterLifecycle.class);
        });
    }
    @SubscribeEvent public static void reload(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent e){e.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> net.minecraft.client.Minecraft.getInstance().execute(PosterImages::clear));}
}
