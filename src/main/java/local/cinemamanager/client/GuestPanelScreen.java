package local.cinemamanager.client;

import local.cinemamanager.InfoTile;
import local.cinemamanager.panels.PanelNetwork;
import local.cinemamanager.panels.PanelLayout;
import local.cinemamanager.panels.PublicPanelPagination;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Read-only vanilla viewer. Public rows have no manager, ownership or transport diagnostics. */
public final class GuestPanelScreen extends Screen {
    private final BlockPos pos;
    private final String identity;
    private PanelNetwork.GuestData data;
    private List<List<GuestPanelText.Line>> pages = List.of();
    private Button previous, next, home, end;
    private int page, ticks, left, top, contentWidth, contentTop, lineHeight, linesPerPage;
    private int textLeft, textTop, textWidth;
    private float canvasScale=1;
    private PanelLayout.Rect textArea=new PanelLayout.Rect(0,0,1,1);
    private PanelLayout.Rect posterArea=new PanelLayout.Rect(0,0,0,0);

    private GuestPanelScreen(PanelNetwork.GuestView packet, PanelNetwork.GuestData data) {
        super(Component.translatable("mycinema.panel.guest_title"));
        this.pos = packet.pos();
        this.identity = packet.identity();
        this.data = data;
    }

    public static void open(PanelNetwork.GuestView packet) {
        PanelNetwork.GuestData data;
        try { data = PanelNetwork.JSON.fromJson(packet.json(), PanelNetwork.GuestData.class); }
        catch (RuntimeException invalid) { return; }
        if (data == null || data.settings() == null || data.scope() == null || data.snapshot() == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof GuestPanelScreen screen
                && screen.pos.equals(packet.pos()) && screen.identity.equals(packet.identity())) {
            screen.update(data);
        } else if (packet.open()) minecraft.setScreen(new GuestPanelScreen(packet, data));
    }

    @Override
    protected void init() {
        contentWidth = Math.max(1, Math.min(520, width - 48));
        left = (width - contentWidth) / 2;
        top = 16;
        contentTop = top + 28;
        lineHeight = font.lineHeight + 2;
        boolean seats = data.scope() == local.cinemamanager.panels.PanelScope.HALL && minecraft != null && minecraft.level != null
                && minecraft.level.hasChunkAt(pos) && (minecraft.level.getBlockState(pos).is(local.cinemamanager.CinemaManager.INFO.get())
                || minecraft.level.getBlockState(pos).is(local.cinemamanager.CinemaManager.WALL_INFO.get()));
        int buttonWidth = Math.max(18, (contentWidth - (seats ? 20 : 16)) / (seats ? 6 : 5));
        int buttonY = height - 36;
        home = addButton("|<", "guest_home", left, buttonY, buttonWidth, () -> turnTo(0));
        previous = addButton("<", "guest_previous", left + buttonWidth + 4, buttonY, buttonWidth, () -> turnTo(page - 1));
        next = addButton(">", "guest_next", left + (buttonWidth + 4) * 2, buttonY, buttonWidth, () -> turnTo(page + 1));
        end = addButton(">|", "guest_end", left + (buttonWidth + 4) * 3, buttonY, buttonWidth, () -> turnTo(pages.size() - 1));
        if (seats) addRenderableWidget(Button.builder(Component.translatable("mycinema.seats.map.entry"), button ->
                local.cinemamanager.client.seats.SeatMapScreen.open(this, pos, identity, ""))
                .bounds(left + (buttonWidth + 4) * 4, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(left + (buttonWidth + 4) * (seats ? 5 : 4), buttonY, buttonWidth, 20).build());
        rebuildPages();
    }

    private Button addButton(String text, String key, int x, int y, int width, Runnable action) {
        Button button = Button.builder(Component.literal(text), ignored -> action.run()).bounds(x, y, width, 20).build();
        button.setTooltip(Tooltip.create(Component.translatable("mycinema.panel." + key)
                .append("\n").append(Component.translatable("mycinema.panel.guest_keys"))));
        return addRenderableWidget(button);
    }

    private void update(PanelNetwork.GuestData nextData) {
        // generatedAt changes on every response. It is not a presentation change.
        boolean changed = !data.settings().equals(nextData.settings()) || data.scope() != nextData.scope()
                || !data.snapshot().timezone().equals(nextData.snapshot().timezone())
                || !data.snapshot().rows().equals(nextData.snapshot().rows())
                || !local.cinemamanager.panels.PublicTimeRange.dayKey(data.snapshot().generatedAt(),data.snapshot().timezone()).equals(local.cinemamanager.panels.PublicTimeRange.dayKey(nextData.snapshot().generatedAt(),nextData.snapshot().timezone()));
        data = nextData;
        if (changed && font != null) rebuildPages();
    }

    private void rebuildPages() {
        canvasScale=data.settings().textPercent()/100f;
        PanelLayout.Areas areas=PanelLayout.areas(contentWidth/canvasScale,Math.max(1,height-contentTop-56)/canvasScale,data.settings().layout(),data.settings().style().posterPercent());
        posterArea=areas.poster();textArea=areas.text();
        textLeft=left+(int)Math.ceil(areas.text().x()*canvasScale);textTop=contentTop+(int)Math.ceil(areas.text().y()*canvasScale);
        textWidth=Math.max(1,(int)Math.floor(areas.text().width()));
        linesPerPage=Math.max(1,(int)Math.floor(areas.text().height()/lineHeight));
        pages = PublicPanelPagination.paginate(GuestPanelText.lines(font, textWidth, data.settings(), data.scope(), data.snapshot(), false), linesPerPage);
        // Preserve manual navigation on refresh and resize, clamping only if content shrinks.
        page = PublicPanelPagination.clampPage(page, pages.size());
        updateButtons();
    }

    private void turnTo(int target) {
        page = PublicPanelPagination.clampPage(target, pages.size());
        updateButtons();
    }

    private void updateButtons() {
        if (previous == null) return;
        previous.active = home.active = page > 0;
        next.active = end.active = page + 1 < pages.size();
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft == null || minecraft.player == null || minecraft.level == null
                || minecraft.player.distanceToSqr(pos.getCenter()) > 64
                || !(minecraft.level.getBlockEntity(pos) instanceof InfoTile tile)
                || !tile.identity.toString().equals(identity) || tile.isOperations() || tile.invalidTerminalRole()) {
            onClose();
            return;
        }
        if (++ticks % 40 == 0) PacketDistributor.sendToServer(new PanelNetwork.GuestRefresh(pos, identity));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(left - 12, 8, left + contentWidth + 12, height - 8, 0xff000000 | data.settings().background());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderPoster(graphics);
        int color = 0xff000000 | data.settings().foreground();
        graphics.drawString(font, title, left, top, 0xff000000 | data.settings().accent(), false);
        Component indicator = Component.translatable("book.pageIndicator", page + 1, Math.max(1, pages.size()));
        graphics.drawString(font, indicator, left + contentWidth - font.width(indicator), top + font.lineHeight + 3, color, false);
        if (!pages.isEmpty()) {
            List<GuestPanelText.Line> lines = pages.get(page);
            var positions=GuestPanelText.positions(font,textArea,lines,data.settings());
            graphics.enableScissor(textLeft,textTop,left+(int)Math.ceil((textArea.x()+textArea.width())*canvasScale),contentTop+(int)Math.ceil((textArea.y()+textArea.height())*canvasScale));
            for (int index = 0; index < lines.size(); index++) {
                GuestPanelText.Line line = lines.get(index);
                var position=positions.get(index);
                graphics.pose().pushPose();graphics.pose().translate(left+position.x()*canvasScale,contentTop+position.y()*canvasScale,0);graphics.pose().scale(position.scale()*canvasScale,position.scale()*canvasScale,1);
                graphics.drawString(font,line.text(),0,0,0xff000000|line.color(),false);
                graphics.pose().popPose();
            }
            graphics.disableScissor();
        }
    }

    private void renderPoster(GuiGraphics graphics){
        if(minecraft==null||minecraft.level==null||!minecraft.level.hasChunkAt(pos)
                ||!(minecraft.level.getBlockEntity(pos) instanceof InfoTile tile)||tile.isRemoved()
                ||!PosterViewState.guestMayDisplay(data.settings().layout(),identity,tile.identity.toString(),data.settings().poster(),tile.publishedPoster))return;
        // Only the live, approved publication enters the existing cache. The packet is never fetched as a source.
        PosterImages.Result image=PosterImages.lookup(tile);
        if(image.status()!=PosterImages.Status.READY||image.texture()==null||image.textureWidth()<1||image.textureHeight()<1)return;
        PanelLayout.Rect imageArea=PanelLayout.contain(posterArea,image.width(),image.height(),data.settings().style());
        int imageWidth=(int)Math.floor(imageArea.width()*canvasScale),imageHeight=(int)Math.floor(imageArea.height()*canvasScale);
        if(imageWidth<1||imageHeight<1)return;
        graphics.enableScissor(left+(int)Math.floor(posterArea.x()*canvasScale),contentTop+(int)Math.floor(posterArea.y()*canvasScale),left+(int)Math.ceil((posterArea.x()+posterArea.width())*canvasScale),contentTop+(int)Math.ceil((posterArea.y()+posterArea.height())*canvasScale));
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        graphics.blit(image.texture(),left+(int)Math.floor(imageArea.x()*canvasScale),contentTop+(int)Math.floor(imageArea.y()*canvasScale),
                imageWidth,imageHeight,0f,0f,image.textureWidth(),image.textureHeight(),image.textureWidth(),image.textureHeight());
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        graphics.disableScissor();
        // Visitor failures stay blank; source and transport diagnostics remain in the owner editor.
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_PAGE_UP -> turnTo(page - 1);
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_PAGE_DOWN -> turnTo(page + 1);
            case GLFW.GLFW_KEY_HOME -> turnTo(0);
            case GLFW.GLFW_KEY_END -> turnTo(pages.size() - 1);
            default -> { return super.keyPressed(keyCode, scanCode, modifiers); }
        }
        return true;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public Component getNarrationMessage() {
        return Component.empty().append(title).append(". ")
                .append(Component.translatable("book.pageIndicator", page + 1, Math.max(1, pages.size())));
    }
}
