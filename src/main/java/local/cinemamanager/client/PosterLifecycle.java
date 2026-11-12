package local.cinemamanager.client;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
public final class PosterLifecycle {
    private PosterLifecycle(){}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e){PosterImages.tick();}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){PosterImages.clear();}
}
