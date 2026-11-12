package local.cinemamanager.client;

import local.cinemamanager.advertising.MirrorNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MirrorConfirmation {
    private MirrorConfirmation(){}
    public static void open(MirrorNetwork.Offer offer){
        var mc=Minecraft.getInstance();var previous=mc.screen;
        mc.setScreen(new ConfirmScreen(accepted->{
            PacketDistributor.sendToServer(new MirrorNetwork.Confirm(offer.token(),accepted));mc.setScreen(previous);
        },Component.translatable("mycinema.mirror."+(offer.revoke()?"revoke_title":"claim_title")),
                Component.translatable("mycinema.mirror."+(offer.revoke()?"revoke_explain":"claim_explain"))));
    }
}
