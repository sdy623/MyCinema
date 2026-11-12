package local.cinemamanager.client;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import local.cinemamanager.CinemaManager;
import me.srrapero720.waterui.core.AbstractParent;
import me.srrapero720.waterui.core.Element;
import me.srrapero720.waterui.screen.WaterScreen;
import me.srrapero720.waterui.widget.Text;
import net.minecraft.network.chat.Component;

/** Scrollable exact-source review. It never supplies policy flags or media paths to the server. */
public final class LocalProgrammeConfirmation extends WaterScreen {
    private final BooleanConsumer callback;
    private final String body;
    private boolean answered;

    public LocalProgrammeConfirmation(String body,BooleanConsumer callback){
        super(CinemaManager.id("film_consent"),448,348);
        this.callback=callback;
        this.body=body;
        registerEvent("consent_confirm",event->answer(true));
        registerEvent("consent_cancel",event->answer(false));
    }

    @Override protected void build(){
        if(get(CinemaManager.id("consent_content")) instanceof AbstractParent content)
            body.lines().forEach(line->content.add(new Text(Component.literal(line.isBlank()?" ":line)).lines(4096).width(Element.FILL)));
    }
    private void answer(boolean accepted){if(answered)return;answered=true;callback.accept(accepted);}
    @Override public void onClose(){answer(false);}
    @Override public boolean isPauseScreen(){return false;}
}
