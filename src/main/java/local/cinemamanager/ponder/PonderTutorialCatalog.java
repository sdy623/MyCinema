package local.cinemamanager.ponder;

import java.util.List;

/** Stable public inventory for MyCinema's interactive Ponder tutorials. */
public final class PonderTutorialCatalog {
    private static final List<SceneSpec> SCENES = List.of(
            new SceneSpec("manager_terminal", "tutorial/manager_terminal", "manager_terminal", 5),
            new SceneSpec("tms_terminal", "tutorial/tms_terminal", "tms_terminal", 5),
            new SceneSpec("cinema_projector", "tutorial/cinema_projector", "cinema_projector", 4),
            new SceneSpec("imax_gt_projector", "tutorial/imax_gt_projector", "imax_gt_projector", 5),
            new SceneSpec("information_panel", "tutorial/information_panel", "information_panel", 4),
            new SceneSpec("seat_guide", "tutorial/seat_guide", "seat_guide", 5),
            new SceneSpec("staff_reader", "tutorial/staff_reader", "staff_reader", 5)
    );

    public static List<SceneSpec> scenes() {
        return SCENES;
    }

    public record SceneSpec(String componentPath, String storyboardPath, String translationPrefix, int textCount) {
        public SceneSpec {
            if (componentPath == null || componentPath.isBlank()) throw new IllegalArgumentException("componentPath");
            if (storyboardPath == null || storyboardPath.isBlank()) throw new IllegalArgumentException("storyboardPath");
            if (translationPrefix == null || translationPrefix.isBlank()) throw new IllegalArgumentException("translationPrefix");
            if (textCount < 1) throw new IllegalArgumentException("textCount");
        }
    }

    private PonderTutorialCatalog() {}
}
