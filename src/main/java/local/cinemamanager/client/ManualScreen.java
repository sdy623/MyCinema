package local.cinemamanager.client;

import local.cinemamanager.manual.ManualContent;
import local.cinemamanager.manual.ManualPagination;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Vanilla book proportions, with font-measured pagination for every language and resource pack. */
public final class ManualScreen extends Screen {
    public static final int TEXT_WIDTH = 114;
    public static final int LINE_HEIGHT = 9;
    public static final int LINES_PER_PAGE = 128 / LINE_HEIGHT;
    private List<ManualPagination.Page<FormattedCharSequence>> pages = List.of();
    private PageButton previous, next;
    private Button previousChapter, nextChapter;
    private int page;
    private int left, top;

    public ManualScreen() { super(Component.translatable("mycinema.manual.title")); }

    public static void open() { Minecraft.getInstance().setScreen(new ManualScreen()); }

    @Override
    protected void init() {
        left = (width - 192) / 2;
        top = Math.max(0, (height - 236) / 2);
        var chapters = new ArrayList<ManualPagination.Chapter<FormattedCharSequence>>();
        for (String chapter : ManualContent.CHAPTERS) {
            Component text = Component.empty()
                    .append(Component.translatable(ManualContent.titleKey(chapter)).withStyle(ChatFormatting.BOLD))
                    .append("\n\n")
                    .append(Component.translatable(ManualContent.bodyKey(chapter)));
            // Keep the exact lines produced by Font; never join and re-wrap them or truncate a chapter.
            chapters.add(new ManualPagination.Chapter<>(chapter, font.split(text, TEXT_WIDTH)));
        }
        pages = ManualPagination.paginate(chapters, LINES_PER_PAGE);
        page = ManualPagination.clampPage(page, pages.size());
        previous = addRenderableWidget(new PageButton(left + 43, top + 157, false, button -> turnTo(page - 1), true));
        next = addRenderableWidget(new PageButton(left + 116, top + 157, true, button -> turnTo(page + 1), true));
        previousChapter = addRenderableWidget(Button.builder(Component.translatable("mycinema.manual.previous_chapter"),
                button -> turnTo(ManualPagination.previousChapter(pages, page))).bounds(width / 2 - 100, top + 194, 98, 18).build());
        nextChapter = addRenderableWidget(Button.builder(Component.translatable("mycinema.manual.next_chapter"),
                button -> turnTo(ManualPagination.nextChapter(pages, page))).bounds(width / 2 + 2, top + 194, 98, 18).build());
        Tooltip keys = Tooltip.create(Component.translatable("mycinema.manual.keys"));
        previousChapter.setTooltip(keys);
        nextChapter.setTooltip(keys);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(width / 2 - 100, top + 216, 200, 20).build());
        updateButtons();
    }

    private void turnTo(int target) {
        page = ManualPagination.clampPage(target, pages.size());
        updateButtons();
    }

    private void updateButtons() {
        previous.visible = page > 0;
        next.visible = page + 1 < pages.size();
        previousChapter.active = page > 0;
        nextChapter.active = !pages.isEmpty() && page - pages.get(page).chapterPage() + pages.get(page).chapterPages() < pages.size();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.blit(BookViewScreen.BOOK_LOCATION, left, top, 0, 0, 192, 192);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        Component indicator = Component.translatable("book.pageIndicator", page + 1, pages.size());
        graphics.drawString(font, indicator, left + 148 - font.width(indicator), top + 16, 0, false);
        if (!pages.isEmpty()) {
            var lines = pages.get(page).lines();
            for (int line = 0; line < lines.size(); line++) {
                graphics.drawString(font, lines.get(line), left + 36, top + 30 + line * LINE_HEIGHT, 0, false);
            }
        }
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

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public Component getNarrationMessage() {
        if (pages.isEmpty()) return title;
        return Component.empty().append(title).append(". ")
                .append(Component.translatable(ManualContent.titleKey(pages.get(page).chapter()))).append(". ")
                .append(Component.translatable("book.pageIndicator", page + 1, pages.size()));
    }
}
