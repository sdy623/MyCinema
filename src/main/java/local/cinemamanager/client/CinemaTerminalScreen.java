package local.cinemamanager.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.TerminalNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** MyCinema's own theater-management screen; it does not depend on private WaterFrames classes. */
public final class CinemaTerminalScreen extends Screen {
    private final BlockPos pos;
    private final String identity;
    private CompoundTag state;
    private int tab;
    private int left;
    private int contentWidth;
    private EditBox url;
    private EditBox seek;
    private final List<EditBox> audience = new ArrayList<>();

    private CinemaTerminalScreen(TerminalNetwork.Snapshot packet) {
        super(Component.translatable("mycinema.terminal.title"));
        pos = packet.pos();
        identity = packet.identity();
        state = packet.state().copy();
    }

    public static void open(TerminalNetwork.Snapshot packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof CinemaTerminalScreen screen
                && screen.pos.equals(packet.pos()) && screen.identity.equals(packet.identity())) screen.update(packet);
        else if (packet.open()) minecraft.setScreen(new CinemaTerminalScreen(packet));
    }

    private void update(TerminalNetwork.Snapshot packet) {
        state = packet.state().copy();
        rebuild();
    }

    @Override
    protected void init() {
        contentWidth = Math.max(280, Math.min(620, width - 32));
        left = (width - contentWidth) / 2;
        int tabWidth = (contentWidth - 8) / 3;
        button(Component.translatable("mycinema.terminal.tab.media"), left, 42, tabWidth, () -> switchTab(0));
        button(Component.translatable("mycinema.terminal.tab.group"), left + tabWidth + 4, 42, tabWidth, () -> switchTab(1));
        button(Component.translatable("mycinema.terminal.tab.audio"), left + (tabWidth + 4) * 2, 42, tabWidth, () -> switchTab(2));
        switch (tab) {
            case 0 -> mediaTab();
            case 1 -> groupTab();
            case 2 -> audioTab();
            default -> throw new IllegalStateException("Unknown TMS tab " + tab);
        }
        int bottom = height - 28;
        button(Component.translatable("mycinema.terminal.refresh"), left, bottom, (contentWidth - 4) / 2,
                () -> send("refresh", new CompoundTag()));
        button(Component.translatable("gui.done"), left + (contentWidth + 4) / 2, bottom,
                (contentWidth - 4) / 2, this::onClose);
    }

    private void mediaTab() {
        boolean locked = managed();
        url = new EditBox(font, left, 82, Math.max(80, contentWidth - 104), 20,
                Component.translatable("mycinema.terminal.media_url"));
        url.setMaxLength(2048);
        url.setValue(state.getString("url"));
        url.setHint(Component.translatable("mycinema.terminal.media_url"));
        url.setEditable(!locked);
        addRenderableWidget(url);
        Button load = button(Component.translatable("mycinema.terminal.load"), left + contentWidth - 100, 82, 100, () -> {
            CompoundTag args = new CompoundTag(); args.putString("url", url.getValue().trim()); send("media", args);
        });
        load.active = !locked;
        int unit = (contentWidth - 12) / 4;
        Button play = button(Component.translatable("mycinema.terminal.play"), left, 114, unit, () -> send("play", new CompoundTag()));
        Button pause = button(Component.translatable("mycinema.terminal.pause"), left + unit + 4, 114, unit, () -> send("pause", new CompoundTag()));
        Button stop = button(Component.translatable("mycinema.terminal.stop"), left + (unit + 4) * 2, 114, unit, () -> send("stop", new CompoundTag()));
        Button loop = button(Component.translatable(state.getBoolean("loop") ? "mycinema.terminal.loop_on" : "mycinema.terminal.loop_off"),
                left + (unit + 4) * 3, 114, unit, () -> { CompoundTag args = new CompoundTag(); args.putBoolean("value", !state.getBoolean("loop")); send("loop", args); });
        play.active = pause.active = stop.active = loop.active = !locked && !state.getString("url").isBlank();
        seek = new EditBox(font, left, 146, Math.max(80, contentWidth - 104), 20,
                Component.translatable("mycinema.terminal.seek_ms"));
        seek.setMaxLength(16);
        seek.setHint(Component.translatable("mycinema.terminal.seek_ms"));
        seek.setEditable(!locked && !state.getBoolean("live"));
        addRenderableWidget(seek);
        Button seekButton = button(Component.translatable("mycinema.terminal.seek"), left + contentWidth - 100, 146, 100, () -> {
            try { CompoundTag args = new CompoundTag(); args.putLong("time", Long.parseLong(seek.getValue().trim())); send("seek", args); }
            catch (NumberFormatException ignored) { }
        });
        seekButton.active = !locked && !state.getBoolean("live");
    }

    private void groupTab() {
        boolean locked = managed();
        int half = (contentWidth - 4) / 2;
        Button bind = button(Component.translatable("mycinema.terminal.bind"), left, 82, half, () -> send("bind", new CompoundTag()));
        Button unlink = button(Component.translatable("mycinema.terminal.unlink"), left + half + 4, 82, half, () -> send("unlink", new CompoundTag()));
        bind.active = unlink.active = !locked;
        button(Component.translatable(state.getBoolean("linked") ? "mycinema.terminal.linked_on" : "mycinema.terminal.linked_off"),
                left, 114, contentWidth, () -> { CompoundTag args = new CompoundTag(); args.putBoolean("value", !state.getBoolean("linked")); send("linked", args); });
        var members = state.getList("projectors", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(2, members.size()); i++) {
            CompoundTag member = members.getCompound(i);
            int index = i;
            Button projector = button(Component.translatable("mycinema.terminal.projector", i + 1,
                            member.getString("style").isBlank() ? "—" : member.getString("style").toUpperCase(Locale.ROOT),
                            BlockPos.of(member.getLong("pos")).toShortString()),
                    left, 150 + i * 28, contentWidth, () -> {
                        CompoundTag selected = members.getCompound(index);
                        CompoundTag args = new CompoundTag(); args.putLong("pos", selected.getLong("pos"));
                        if (selected.hasUUID("id")) args.putUUID("id", selected.getUUID("id"));
                        send("projector", args);
                    });
            projector.active = !state.getBoolean("linked") && member.hasUUID("id");
        }
    }

    private void audioTab() {
        int unit = (contentWidth - 8) / 3;
        audioButton("screen", left, unit);
        audioButton("audience", left + unit + 4, unit);
        audioButton("projector", left + (unit + 4) * 2, unit);
        audience.clear();
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            EditBox field = new EditBox(font, left + i * (unit + 4), 126, unit, 20,
                    Component.translatable("mycinema.terminal.axis." + axes[i]));
            field.setMaxLength(24);
            String key = "audience_" + axes[i];
            if (state.contains(key)) field.setValue(Double.toString(state.getDouble(key)));
            field.setHint(Component.translatable("mycinema.terminal.axis." + axes[i]));
            audience.add(field); addRenderableWidget(field);
        }
        button(Component.translatable("mycinema.terminal.save_audience"), left, 158, contentWidth, () -> {
            try {
                CompoundTag args = new CompoundTag();
                args.putDouble("x", Double.parseDouble(audience.get(0).getValue().trim()));
                args.putDouble("y", Double.parseDouble(audience.get(1).getValue().trim()));
                args.putDouble("z", Double.parseDouble(audience.get(2).getValue().trim()));
                send("audience", args);
            } catch (NumberFormatException ignored) { }
        });
    }

    private void audioButton(String mode, int x, int width) {
        Button button = button(Component.translatable("mycinema.terminal.audio." + mode), x, 82, width, () -> {
            CompoundTag args = new CompoundTag(); args.putString("mode", mode); send("audio", args);
        });
        button.active = !state.getString("audio_mode").equals(mode);
    }

    private Button button(Component text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(text, ignored -> action.run()).bounds(x, y, Math.max(20, width), 20).build());
    }

    private void switchTab(int next) { tab = next; rebuild(); }
    void qaSwitchTab(int next) {
        if (!Boolean.getBoolean("mycinema.clientQa")) throw new IllegalStateException("TMS QA controls are disabled");
        if (next < 0 || next > 2) throw new IllegalArgumentException("Unknown TMS QA tab " + next);
        switchTab(next);
    }
    private void rebuild() { clearWidgets(); init(); }
    private boolean managed() { return state.getBoolean("programme_managed") || state.getBoolean("library_managed"); }

    private void send(String action, CompoundTag args) {
        PacketDistributor.sendToServer(new TerminalNetwork.Request(pos, identity, action, args));
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft == null || minecraft.level == null || minecraft.player == null
                || minecraft.player.distanceToSqr(pos.getCenter()) > 64
                || !(minecraft.level.getBlockEntity(pos) instanceof CinemaTerminalTile terminal)
                || !terminal.identity().toString().equals(identity)) onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(left - 8, 10, left + contentWidth + 8, height - 35, 0xD0182436);
        graphics.drawCenteredString(font, title, width / 2, 18, 0xFFF4E7C1);
        graphics.drawCenteredString(font, Component.translatable(managed() ? "mycinema.terminal.authority_managed" : "mycinema.terminal.authority_manual"), width / 2, 29, managed() ? 0xFFFFCC66 : 0xFF80E8E8);
        if (tab == 0) {
            graphics.drawString(font, Component.translatable("mycinema.terminal.playback_status",
                    state.getBoolean("active") ? state.getBoolean("paused") ? Component.translatable("mycinema.terminal.paused") : Component.translatable("mycinema.terminal.playing") : Component.translatable("mycinema.terminal.stopped"),
                    duration(state.getLong("time")), duration(state.getLong("duration"))), left, 178, 0xFFE0E8EE, false);
            if (managed()) graphics.drawString(font, Component.translatable("mycinema.terminal.managed_help"), left, 198, 0xFFFFCC66, false);
        } else if (tab == 1) {
            graphics.drawString(font, Component.translatable("mycinema.terminal.group_status",
                    state.getList("projectors", Tag.TAG_COMPOUND).size(), state.getBoolean("playback_available") ? Component.translatable("mycinema.terminal.ready") : Component.translatable("mycinema.terminal.unavailable")), left, 214, 0xFFE0E8EE, false);
            if (state.getBoolean("linked")) graphics.drawString(font, Component.translatable("mycinema.terminal.unlink_help"), left, 234, 0xFFB9C7D8, false);
        } else {
            graphics.drawString(font, Component.translatable("mycinema.terminal.audio_status", Component.translatable("mycinema.terminal.audio_status." + state.getString("audio_status"))), left, 194, 0xFFE0E8EE, false);
            if (state.contains("audio_x")) graphics.drawString(font, Component.translatable("mycinema.terminal.audio_coordinates",
                    coordinate(state.getDouble("audio_x")), coordinate(state.getDouble("audio_y")), coordinate(state.getDouble("audio_z"))), left, 214, 0xFFB9C7D8, false);
        }
        if (state.contains("notice")) graphics.drawCenteredString(font, message(state.getString("notice")), width / 2, height - 47, 0xFFFF7777);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private static Component message(String value) { return value.startsWith("mycinema.") ? Component.translatable(value) : Component.literal(value); }
    private static String coordinate(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static String duration(long ms) { long seconds = Math.max(0, ms) / 1000; return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    @Override public boolean isPauseScreen() { return false; }
}
