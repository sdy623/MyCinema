package local.cinemamanager.ponder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Verifies the public Ponder tutorial catalog, translations and loadable structure resources. */
public final class PonderTutorialChecks {
    private static final Set<String> REQUIRED_COMPONENTS = Set.of(
            "manager_terminal",
            "tms_terminal",
            "cinema_projector",
            "imax_gt_projector",
            "information_panel",
            "seat_guide",
            "staff_reader"
    );
    private static int checks;

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        List<PonderTutorialCatalog.SceneSpec> scenes = PonderTutorialCatalog.scenes();
        check(scenes.size() == REQUIRED_COMPONENTS.size(), "seven operational tutorial scenes");
        check(scenes.stream().map(PonderTutorialCatalog.SceneSpec::componentPath).collect(java.util.stream.Collectors.toSet()).equals(REQUIRED_COMPONENTS),
                "every core cinema component has a tutorial");
        check(new HashSet<>(scenes.stream().map(PonderTutorialCatalog.SceneSpec::storyboardPath).toList()).size() == scenes.size(),
                "storyboard paths are unique");
        check(new HashSet<>(scenes.stream().map(PonderTutorialCatalog.SceneSpec::translationPrefix).toList()).size() == scenes.size(),
                "translation prefixes are unique");

        JsonObject english = readJson(Path.of("src/main/resources/assets/mycinema/lang/en_us.json"));
        JsonObject chinese = readJson(Path.of("src/main/resources/assets/mycinema/lang/zh_cn.json"));
        for (PonderTutorialCatalog.SceneSpec scene : scenes) {
            check(scene.textCount() >= 3, scene.componentPath() + " has a useful multi-step tutorial");
            verifyTranslations(english, scene, "English");
            verifyTranslations(chinese, scene, "Chinese");
            verifyStructure(Path.of("src/main/resources/assets/mycinema/ponder", scene.storyboardPath() + ".nbt"), scene.componentPath());
        }

        check(english.has("mycinema.ponder.tag.cinema_operations"), "English tutorial category title");
        check(english.has("mycinema.ponder.tag.cinema_operations.description"), "English tutorial category description");
        check(chinese.has("mycinema.ponder.tag.cinema_operations"), "Chinese tutorial category title");
        check(chinese.has("mycinema.ponder.tag.cinema_operations.description"), "Chinese tutorial category description");
        System.out.println("PASS: " + checks + " Ponder tutorial checks; seven components, bilingual steps and gzip structure blueprints.");
    }

    private static JsonObject readJson(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void verifyTranslations(JsonObject language, PonderTutorialCatalog.SceneSpec scene, String languageName) {
        String base = "mycinema.ponder." + scene.translationPrefix();
        check(language.has(base + ".header"), languageName + " header for " + scene.componentPath());
        for (int i = 1; i <= scene.textCount(); i++) {
            check(language.has(base + ".text_" + i), languageName + " text " + i + " for " + scene.componentPath());
        }
    }

    private static void verifyStructure(Path path, String component) throws IOException {
        check(Files.isRegularFile(path), "structure exists for " + component);
        try (InputStream raw = Files.newInputStream(path);
             DataInputStream nbt = new DataInputStream(new GZIPInputStream(raw))) {
            check(nbt.readUnsignedByte() == 10, "structure root is an NBT compound for " + component);
            check(nbt.readUnsignedShort() == 0, "structure root is unnamed for " + component);
        }
    }

    private PonderTutorialChecks() {}
}
