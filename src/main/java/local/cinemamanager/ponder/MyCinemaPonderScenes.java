package local.cinemamanager.ponder;

import local.cinemamanager.CinemaManager;
import local.cinemamanager.access.AccessModule;
import local.cinemamanager.seats.SeatsModule;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.PonderStoryBoard;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/** Seven short, task-oriented tutorials attached to the items players actually hold. */
public final class MyCinemaPonderScenes {
    private static final BlockPos CENTER = new BlockPos(3, 1, 3);

    public static PonderStoryBoard storyboard(String componentPath) {
        return switch (componentPath) {
            case "manager_terminal" -> MyCinemaPonderScenes::managerTerminal;
            case "tms_terminal" -> MyCinemaPonderScenes::tmsTerminal;
            case "cinema_projector" -> MyCinemaPonderScenes::cinemaProjector;
            case "imax_gt_projector" -> MyCinemaPonderScenes::imaxGtProjector;
            case "information_panel" -> MyCinemaPonderScenes::informationPanel;
            case "seat_guide" -> MyCinemaPonderScenes::seatGuide;
            case "staff_reader" -> MyCinemaPonderScenes::staffReader;
            default -> throw new IllegalArgumentException("Unknown Ponder component: " + componentPath);
        };
    }

    private static void managerTerminal(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "manager_terminal", "Run a cinema from the Control Terminal");
        text(scene, util, 70, "The Control Terminal is the authority for one cinema. Right-click it to name the venue, set its time zone and create halls.", CENTER);
        input(scene, util, CENTER, new ItemStack(CinemaManager.MANAGER_ITEM.get()), false);
        text(scene, util, 70, "Use a Linking Card on this terminal, choose a hall, then use the card on that hall's TMS.", new BlockPos(3, 1, 3));
        input(scene, util, CENTER, new ItemStack(CinemaManager.LINKER.get()), false);
        text(scene, util, 70, "Build the film catalogue and ordered programmes before adding scheduled shows. A saved show does not enable automation by itself.", new BlockPos(1, 1, 3));
        text(scene, util, 70, "Enable scheduling only after checking the cinema time zone, source permissions and the hall's loaded TMS.", CENTER);
        text(scene, util, 70, "Information panels and scene outputs follow the hall bindings while playback authority remains at the terminal and TMS.", new BlockPos(5, 1, 3));
        finish(scene);
    }

    private static void tmsTerminal(SceneBuilder scene, SceneBuildingUtil util) {
        begin(scene, util, "tms_terminal", "Prepare and control a hall with TMS");
        text(scene, util, 70, "Each hall uses one TMS. It owns the media session and shares the decoded picture with its linked projector endpoints.", CENTER);
        text(scene, util, 70, "Open Projection Group and bind the nearby projector endpoints. Group actions fail rather than silently dropping a missing endpoint.", new BlockPos(3, 1, 2));
        input(scene, util, CENTER, new ItemStack(CinemaManager.TMS_TERMINAL_ITEM.get()), false);
        text(scene, util, 70, "Load a permitted URL or shared local path. Loading leaves the source paused; press Play only after checking the hall.", CENTER);
        text(scene, util, 70, "Choose the audio origin: screen centre, calibrated audience centre, or the projector midpoint when that group is available.", new BlockPos(3, 1, 2));
        text(scene, util, 70, "When the TMS says it is programme-managed, use the bound Control Terminal instead of trying to override it locally.", CENTER);
        finish(scene);
    }

    private static void cinemaProjector(SceneBuilder scene, SceneBuildingUtil util) {
        BlockPos projector = new BlockPos(3, 1, 2);
        begin(scene, util, "cinema_projector", "Install a cinema projector");
        text(scene, util, 70, "Place the projector on a clear, solid floor with its optical face aimed at the screen.", projector);
        input(scene, util, projector, new ItemStack(CinemaManager.CINEMA_PROJECTOR_ITEM.get()), false);
        text(scene, util, 70, "Bind it from the hall's TMS. Linked controls keep group playback and calibration on the terminal.", new BlockPos(3, 1, 5));
        text(scene, util, 70, "The projector does not start another decoder: it renders the TMS display, so synchronized endpoints share one media clock.", projector);
        text(scene, util, 70, "Turn linked controls off before opening one projector for individual WaterFrames calibration, then restore the group.", projector);
        finish(scene);
    }

    private static void imaxGtProjector(SceneBuilder scene, SceneBuildingUtil util) {
        BlockPos projector = new BlockPos(3, 1, 2);
        begin(scene, util, "imax_gt_projector", "Install the IMAX GT projector");
        scene.scaleSceneView(.82f);
        text(scene, util, 70, "The IMAX GT item uses the dedicated parallel-cabinet artwork and a separate public registry ID.", projector);
        input(scene, util, projector, new ItemStack(CinemaManager.IMAX_GT_PROJECTOR_ITEM.get()), false);
        text(scene, util, 70, "Placement reserves the complete 3 × 2 × 3 machine volume. Clear the space and support every required floor cell first.", projector);
        text(scene, util, 70, "One placed IMAX GT machine is one projector endpoint, even though its model shows the parallel cabinet assembly.", projector);
        text(scene, util, 70, "Bind endpoints from the TMS and calibrate their projected image faces before selecting the group audio origin.", new BlockPos(3, 1, 5));
        text(scene, util, 70, "For a 1.43:1 auditorium, prepare matching source framing and screen geometry; MyCinema does not crop a film into that ratio automatically.", projector);
        finish(scene);
    }

    private static void informationPanel(SceneBuilder scene, SceneBuildingUtil util) {
        BlockPos panel = new BlockPos(4, 1, 3);
        begin(scene, util, "information_panel", "Publish hall information");
        text(scene, util, 70, "The Information Panel is a public, read-only view of the cinema's current and next shows.", panel);
        text(scene, util, 70, "Select a hall on a Linking Card at the Control Terminal, then use the card on this panel.", new BlockPos(2, 1, 3));
        input(scene, util, panel, new ItemStack(CinemaManager.LINKER.get()), false);
        text(scene, util, 70, "Bind it to a hall for a single screen, or configure the supported lobby view when you need the venue schedule.", panel);
        text(scene, util, 70, "Poster and programme data come from the authoritative catalogue; the panel never gains playback controls.", panel);
        finish(scene);
    }

    private static void seatGuide(SceneBuilder scene, SceneBuildingUtil util) {
        BlockPos guide = new BlockPos(3, 1, 1);
        BlockPos firstSeat = new BlockPos(1, 1, 4);
        begin(scene, util, "seat_guide", "Number seats and mark rows");
        text(scene, util, 70, "Place cinema seats on solid full-height support, facing the screen, with a clear footwell and headroom for safe exit.", firstSeat);
        text(scene, util, 70, "Use the Seat Binding Card on the Control Terminal first, then choose a hall, zone, row and starting seat number.", guide);
        input(scene, util, guide, new ItemStack(SeatsModule.BINDER.get()), false);
        text(scene, util, 70, "Continuous numbering records only successful new seat clicks. Whole-row numbering previews the first-to-last straight row before one atomic save.", new BlockPos(3, 1, 4));
        text(scene, util, 70, "Place a Seat / Row Guide where guests can see it and configure the row or seat range it should display.", guide);
        text(scene, util, 70, "Breaking and replacing a chair creates a new physical identity; remove the old binding explicitly before registering its replacement.", firstSeat);
        finish(scene);
    }

    private static void staffReader(SceneBuilder scene, SceneBuildingUtil util) {
        BlockPos reader = new BlockPos(2, 1, 3);
        BlockPos door = new BlockPos(4, 1, 3);
        begin(scene, util, "staff_reader", "Configure staff access");
        text(scene, util, 70, "The Staff Reader controls a local staff entry. It grants door access, not cinema playback authority.", reader);
        text(scene, util, 70, "With the Access Pairing Card, select the Control Terminal, then this reader, then the cinema door it releases.", door);
        input(scene, util, reader, new ItemStack(AccessModule.PAIRING_CARD.get()), false);
        text(scene, util, 70, "Open the reader as the cinema owner to issue role-limited staff cards and review the current configuration.", reader);
        text(scene, util, 70, "Use a written Staff Card on the reader. Every entry rechecks the live cinema, role and paired door.", reader);
        input(scene, util, reader, new ItemStack(AccessModule.CARD.get()), false);
        text(scene, util, 70, "The paired inside exit remains the recovery path; do not use staff cards as a substitute for emergency egress design.", door);
        finish(scene);
    }

    private static void begin(SceneBuilder scene, SceneBuildingUtil util, String key, String title) {
        scene.title(key, title);
        scene.configureBasePlate(0, 0, 7);
        scene.showBasePlate();
        scene.idle(10);
        scene.world().showSection(util.select().layersFrom(1), Direction.DOWN);
        scene.idle(20);
    }

    private static void text(SceneBuilder scene, SceneBuildingUtil util, int duration, String fallback, BlockPos target) {
        scene.overlay().showText(duration)
                .text(fallback)
                .colored(PonderPalette.WHITE)
                .pointAt(util.vector().topOf(target))
                .placeNearTarget()
                .attachKeyFrame();
        scene.idle(duration + 5);
    }

    private static void input(SceneBuilder scene, SceneBuildingUtil util, BlockPos target, ItemStack stack, boolean sneak) {
        var input = scene.overlay().showControls(util.vector().topOf(target), Pointing.DOWN, 35)
                .rightClick()
                .withItem(stack);
        if (sneak) input.whileSneaking();
        scene.idle(40);
    }

    private static void finish(SceneBuilder scene) {
        scene.idle(10);
        scene.markAsFinished();
    }

    private MyCinemaPonderScenes() {}
}
