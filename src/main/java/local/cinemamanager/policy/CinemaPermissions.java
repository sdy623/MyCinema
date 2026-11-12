package local.cinemamanager.policy;

import com.mojang.authlib.GameProfile;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import local.cinemamanager.media.MediaSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-authoritative MyCinema policy; no dependency-private permission nodes or owner bypasses. */
public final class CinemaPermissions {
    private static final Set<String> TRUSTED_HOSTS = Set.of(
            "discord.com", "discordapp.com", "githubusercontent.com", "googlevideo.com",
            "imgur.com", "soundcloud.com", "twitch.tv", "youtu.be", "youtube.com");

    private static final ModConfigSpec.BooleanValue usePermissionsAPI;
    private static final ModConfigSpec.BooleanValue useForAnyone;
    private static final ModConfigSpec.BooleanValue useRemotes;
    private static final ModConfigSpec.BooleanValue useBindingRemotes;
    private static final ModConfigSpec.BooleanValue useInSurvival;
    private static final ModConfigSpec.BooleanValue useInAdventure;
    private static final ModConfigSpec.BooleanValue allowSaving;
    private static final ModConfigSpec.BooleanValue useMasterModeOnRedstone;
    public static final ModConfigSpec SPEC;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("permissions");
        usePermissionsAPI = builder.comment("Fail closed to server operators when an external permission provider is selected.")
                .define("usePermissionsAPI", false);
        useForAnyone = builder.define("useForAnyone", true);
        useRemotes = builder.define("useRemotes", true);
        useBindingRemotes = builder.define("useBindingRemotes", true);
        useInSurvival = builder.define("useInSurvival", true);
        useInAdventure = builder.define("useInAdventure", true);
        allowSaving = builder.define("allowSaving", true);
        builder.pop();
        builder.push("redstone");
        useMasterModeOnRedstone = builder.define("useMasterModeOnRedstone", false);
        builder.pop();
        SPEC = builder.build();
    }

    public static boolean isWhitelisted(String source) {
        if (source == null || !source.equals(MediaSource.normalize(source))) return false;
        if (source.startsWith("water://")) return true;
        try {
            URI uri = URI.create(source);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null) return false;
            host = host.toLowerCase(Locale.ROOT);
            for (String allowed : TRUSTED_HOSTS)
                if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
            return false;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static boolean canSave(Player actor, String source) {
        if (actor == null || source == null) return false;
        if (source.isEmpty()) return true;
        if (!source.equals(MediaSource.normalize(source))) return false;
        if (isAdmin(actor)) return true;
        return !usePermissionsAPI.get() && allowSaving.get() && isWhitelisted(source);
    }

    public static boolean canSavePosterOffline(MinecraftServer server, GameProfile author, String source) {
        if (server == null || author == null || author.getId() == null || !server.isSameThread()
                || source == null || !source.equals(MediaSource.normalize(source))) return false;
        if (profileAdmin(server, author)) return true;
        return !usePermissionsAPI.get() && allowSaving.get() && isWhitelisted(source);
    }

    public static boolean canAutomateTheater(UUID owner, GameType mode, Block block, String source, boolean assignsSource) {
        if (!canAutomateControls(owner, mode, block)) return false;
        if (!assignsSource) return true;
        return source != null && !source.isBlank() && source.equals(MediaSource.normalize(source)) && isWhitelisted(source);
    }

    public static boolean canAutomateLocalProgramme(MinecraftServer server, UUID owner, GameType mode, Block block, String source) {
        if (server == null || owner == null || !server.isSameThread() || !MediaSource.isNormalizedLocal(source)
                || !canAutomateControls(owner, mode, block)) return false;
        return profileAdmin(server, profile(server, owner));
    }

    public static boolean canInteractBlock(Player actor, Block block) {
        return actor != null && block != null && (isAdmin(actor)
                || !usePermissionsAPI.get() && useForAnyone.get() && modeAllowed(gameType(actor)));
    }

    public static boolean canInteractRemote(Player actor) {
        return actor != null && (isAdmin(actor) || !usePermissionsAPI.get() && useRemotes.get()
                && useForAnyone.get() && modeAllowed(gameType(actor)));
    }

    public static boolean canBindRemote(Player actor) {
        return actor != null && (isAdmin(actor) || !usePermissionsAPI.get() && useBindingRemotes.get()
                && useForAnyone.get() && modeAllowed(gameType(actor)));
    }

    public static boolean canMirrorOffline(MinecraftServer server, UUID owner, GameType mode, Block block) {
        if (server == null || owner == null || block == null || !server.isSameThread() || !modeAllowed(mode)) return false;
        return isCurrentMirrorAdministrator(server, owner)
                || !usePermissionsAPI.get() && useForAnyone.get() && useRemotes.get() && useBindingRemotes.get();
    }

    public static boolean isCurrentMirrorAdministrator(MinecraftServer server, UUID identity) {
        return server != null && identity != null && server.isSameThread() && profileAdmin(server, profile(server, identity));
    }

    public static boolean isAdmin(Player actor) {
        return actor != null && actor.hasPermissions(4);
    }

    public static boolean useMasterModeRedstone() {
        return useMasterModeOnRedstone.get();
    }

    private static boolean canAutomateControls(UUID owner, GameType mode, Block block) {
        return owner != null && block != null && modeAllowed(mode) && !usePermissionsAPI.get()
                && useForAnyone.get() && useRemotes.get() && useBindingRemotes.get();
    }

    private static boolean modeAllowed(GameType mode) {
        return mode != null && mode != GameType.SPECTATOR
                && (mode != GameType.SURVIVAL || useInSurvival.get())
                && (mode != GameType.ADVENTURE || useInAdventure.get());
    }

    private static GameType gameType(Player actor) {
        if (actor instanceof ServerPlayer serverPlayer) return serverPlayer.gameMode.getGameModeForPlayer();
        if (actor.isSpectator()) return GameType.SPECTATOR;
        return actor.isCreative() ? GameType.CREATIVE : GameType.SURVIVAL;
    }

    private static GameProfile profile(MinecraftServer server, UUID identity) {
        GameProfile single = server.getSingleplayerProfile();
        return single != null && identity.equals(single.getId()) ? single : new GameProfile(identity, "");
    }

    private static boolean profileAdmin(MinecraftServer server, GameProfile profile) {
        try {
            return profile != null && server.getProfilePermissions(profile) >= 4;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private CinemaPermissions() {}
}
