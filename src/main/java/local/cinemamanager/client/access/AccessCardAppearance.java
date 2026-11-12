package local.cinemamanager.client.access;

import local.cinemamanager.CinemaManager;
import local.cinemamanager.access.AccessModule;
import local.cinemamanager.access.StaffCardItem;
import net.minecraft.client.renderer.item.ItemProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Inventory readability only. Door authorization reads the server grant book, never this property. */
@EventBusSubscriber(modid=CinemaManager.ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
public final class AccessCardAppearance {
    private AccessCardAppearance(){}
    @SubscribeEvent public static void setup(FMLClientSetupEvent event){
        event.enqueueWork(()->ItemProperties.register(AccessModule.CARD.get(),CinemaManager.id("card_state"),
                (stack,level,entity,seed)->StaffCardItem.visualState(stack)));
    }
}
