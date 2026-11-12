package local.cinemamanager.ponder;

import local.cinemamanager.CinemaManager;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/** Registers MyCinema tutorials without bundling or modifying Ponder itself. */
public final class MyCinemaPonderPlugin implements PonderPlugin {
    public static final ResourceLocation CINEMA_OPERATIONS = CinemaManager.id("cinema_operations");

    @Override
    public String getModId() {
        return CinemaManager.ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        for (PonderTutorialCatalog.SceneSpec spec : PonderTutorialCatalog.scenes()) {
            helper.forComponents(helper.asLocation(spec.componentPath()))
                    .addStoryBoard(spec.storyboardPath(), MyCinemaPonderScenes.storyboard(spec.componentPath()), CINEMA_OPERATIONS);
        }
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.registerTag(CINEMA_OPERATIONS)
                .title("Cinema operations")
                .description("Build, connect and operate a complete MyCinema venue")
                .item(CinemaManager.MANAGER_ITEM.get())
                .addToIndex()
                .register();
    }
}
