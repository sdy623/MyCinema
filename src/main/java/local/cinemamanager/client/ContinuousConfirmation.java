package local.cinemamanager.client;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
/** Reviewing a cinema action must not pause the integrated server's scheduler or media clock. */
public final class ContinuousConfirmation extends ConfirmScreen {
    public ContinuousConfirmation(BooleanConsumer callback,Component title,Component message){super(callback,title,message);}
    public ContinuousConfirmation(BooleanConsumer callback,Component title,Component message,Component yes,Component no){super(callback,title,message,yes,no);}
    @Override public boolean isPauseScreen(){return false;}
}
