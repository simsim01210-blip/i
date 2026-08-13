package kr.planetearth.minimap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.function.IntConsumer;

abstract class MinimapEditorScreenBase extends Screen {
    // Kept small on purpose: a high GUI scale on a 4K display can make even a modest
    // scaled-pixel size render as a large box in real screen pixels, so both boxes need
    // to be shrinkable well past what a 1080p-tuned floor would normally allow.
    private static final int MAIN_MIN_SIZE = 24;
    private static final int OVERLAY_MIN_SIZE = 24;
    private static final int OVERLAY_MAX_SIZE = 900;

    private enum EditTarget { MAIN, OVERLAY }

    private EditTarget target = EditTarget.MAIN;
    private boolean dragging;
    private boolean resizing;
    private boolean resizeLeft;
    private boolean resizeRight;
    private boolean resizeTop;
    private boolean resizeBottom;
    private int resizeOriginalLeft;
    private int resizeOriginalTop;
    private int resizeOriginalRight;
    private int resizeOriginalBottom;
    private double dragOffsetX;
    private double dragOffsetY;
    // The status bar (biome/clock) is a separate, always-present element while editing
    // the main map — not its own mode — so it gets its own independent drag state
    // instead of sharing the fields above with the main box.
    private boolean draggingStatusBar;
    private double statusDragOffsetX;
    private double statusDragOffsetY;

    protected MinimapEditorScreenBase() {
        super(Text.literal("PlanetEarth 미니맵 편집"));
    }

    @Override
    protected void init() {
        rebuildControls();
    }

    private void rebuildControls() {
        clearChildren();
        MinimapConfig config = PlanetEarthMinimapClient.config;
        int left = Math.max(8, width - 340);
        int right = left + 164;
        int y = 28;

        // None of these settings apply to the overlay box (its own toggles/size live in
        // the N-menu sidebar and by dragging the box itself), so while editing it the
        // whole panel is skipped rather than sitting there cluttering the preview.
        if (target == EditTarget.MAIN) {
            addSoundButton(toggleLabel("지도", config.enabled), left, y, button -> {
                config.enabled = !config.enabled;
                rebuildControls();
            });
            addSoundButton(toggleLabel("그리드", config.showGrid), right, y, button -> {
                config.showGrid = !config.showGrid;
                rebuildControls();
            });
            y += 24;

            // Turning this off is the single biggest FPS lever this menu has: dense
            // territory near a city can mean hundreds of fills redrawn every frame.
            addSoundButton(toggleLabel("영역 색칠", config.showAreaOverlay), left, y, button -> {
                config.showAreaOverlay = !config.showAreaOverlay;
                rebuildControls();
            });
            // Waypoint visibility/size already live in the N-menu sidebar — no need for
            // a second copy of the same toggle here, so 상태 바 shares this row instead
            // of sitting alone with an empty half.
            addSoundButton(toggleLabel("상태 바", config.showStatusBar), right, y, button -> {
                config.showStatusBar = !config.showStatusBar;
                rebuildControls();
            });
            y += 24;

            addSoundButton(toggleLabel("플레이어", config.showPlayers), left, y, button -> {
                config.showPlayers = !config.showPlayers;
                rebuildControls();
            });
            addSoundButton(toggleLabel("얼굴", config.showPlayerFaces), right, y, button -> {
                config.showPlayerFaces = !config.showPlayerFaces;
                rebuildControls();
            });
            y += 24;

            addSoundButton(toggleLabel("닉네임", config.showPlayerNames), left, y, button -> {
                config.showPlayerNames = !config.showPlayerNames;
                rebuildControls();
            });
            addSoundButton(toggleLabel("효과음", config.uiSoundsEnabled), right, y, button -> {
                config.uiSoundsEnabled = !config.uiSoundsEnabled;
                rebuildControls();
            });
            y += 24;

            addDrawableChild(new IntSlider(left, y, 160, "얼굴 크기", "", 2, 16, 1,
                    config.playerFaceSize, value -> config.playerFaceSize = value));
            addDrawableChild(new IntSlider(right, y, 160, "이름 크기", "%", 25, 150, 5,
                    config.playerNameScalePercent, value -> config.playerNameScalePercent = value));
            y += 24;

            addDrawableChild(new IntSlider(left, y, 160, "내 위치 크기", "", 7, 17, 1,
                    config.selfMarkerSize, value -> config.selfMarkerSize = value));
            addDrawableChild(new IntSlider(right, y, 160, "지도 배율", "", 0, 6, 1,
                    config.zoom, value -> config.zoom = value));
            y += 24;

            addDrawableChild(new IntSlider(left, y, 324, "상태 바 크기", "%", 25, 200, 10,
                    config.statusBarScalePercent, value -> config.statusBarScalePercent = value));
            y += 24;

            addDrawableChild(new IntSlider(left, y, 324, "효과음 음량", "%", 0, 100, 5,
                    config.uiSoundVolumePercent, value -> config.uiSoundVolumePercent = value));
            y += 24;

            // Bundles several individually-heavier settings into one switch for weaker
            // PCs — see MinimapConfig.lowSpecMode for exactly what it changes.
            addSoundButton(toggleLabel("저사양 모드", config.lowSpecMode), left, y, 324, button -> {
                config.lowSpecMode = !config.lowSpecMode;
                LiveAtlasTileManager.applyLowSpecMode(config.lowSpecMode);
                rebuildControls();
            });
            y += 24;

            addSoundButton(Text.literal("기본값"), left, y, button -> {
                config.width = 150;
                config.height = 150;
                config.playerFaceSize = 8;
                config.playerNameScalePercent = 75;
                config.selfMarkerSize = 9;
                config.zoom = 2;
                config.showAreaOverlay = true;
                config.showWaypoints = true;
                config.waypointShape = WaypointPalette.SHAPE_PIN;
                config.overlayMapWidth = 500;
                config.overlayMapHeight = 500;
                config.overlayMapOffsetX = 0;
                config.overlayMapOffsetY = 0;
                config.showStatusBar = true;
                config.statusBarX = 12;
                config.statusBarY = 170;
                config.statusBarScalePercent = 100;
                config.lowSpecMode = false;
                LiveAtlasTileManager.applyLowSpecMode(false);
                fitToScreen();
                rebuildControls();
            });
            addSoundButton(Text.literal("저장하고 닫기"), right, y, button -> close());
        }

        addSoundButton(Text.literal(target == EditTarget.MAIN
                ? "보조 맵 편집으로 ▶" : "◀ 기본 맵 편집으로"), 10, height - 46, button -> {
            target = target == EditTarget.MAIN ? EditTarget.OVERLAY : EditTarget.MAIN;
            rebuildControls();
        });
    }

    private void addSoundButton(Text text, int x, int y, ButtonWidget.PressAction action) {
        addSoundButton(text, x, y, 160, action);
    }

    private void addSoundButton(Text text, int x, int y, int width, ButtonWidget.PressAction action) {
        addDrawableChild(ButtonWidget.builder(text, button -> {
                    playControlSound(MinecraftClient.getInstance().getSoundManager());
                    action.onPress(button);
                })
                .dimensions(x, y, width, 20)
                .build());
    }

    private Text toggleLabel(String name, boolean enabled) {
        return Text.literal(name + ": " + (enabled ? "켜짐" : "꺼짐"));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xB0101010);
        MinimapConfig config = PlanetEarthMinimapClient.config;

        // Only the map box actually being edited is previewed — drawing the other one
        // too (even without handles) meant the big secondary-map box, and its full-size
        // loading animation whenever its tiles weren't ready, would appear before you
        // had ever switched to editing it. The status bar is different: it belongs with
        // the main map and is always shown (and draggable) alongside it.
        if (target == EditTarget.OVERLAY) {
            MinimapHud.drawMap(context, overlayX(), overlayY(), overlayWidth(), overlayHeight(),
                    true, config.overlayZoom, 0xE0153427);
        } else {
            MinimapHud.drawMap(context, config.x, config.y, config.width, config.height, true);
            int barX = statusBarX();
            int barY = statusBarY();
            int barWidth = MinimapHud.statusBarWidth();
            int barHeight = MinimapHud.statusBarHeight();
            MinimapHud.drawStatusBar(context, barX, barY);
            // A plain highlight border — this box only ever moves, so there are no
            // resize handles to draw the way the map box gets.
            context.fill(barX - 2, barY - 2, barX + barWidth + 2, barY - 1, 0xFFFFFFFF);
            context.fill(barX - 2, barY + barHeight + 1, barX + barWidth + 2, barY + barHeight + 2, 0xFFFFFFFF);
            context.fill(barX - 2, barY - 2, barX - 1, barY + barHeight + 2, 0xFFFFFFFF);
            context.fill(barX + barWidth + 1, barY - 2, barX + barWidth + 2, barY + barHeight + 2, 0xFFFFFFFF);
        }

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFFFF);
        String hint = target == EditTarget.OVERLAY
                ? "보조 맵 편집 중 · 안쪽: 이동 / 모든 경계와 모서리: 크기 변경"
                : "기본 맵 편집 중 · 안쪽: 이동 / 경계·모서리: 크기 변경 · 상태 바(바이옴·시계)도 드래그로 이동 가능";
        context.drawTextWithShadow(textRenderer, Text.literal(hint), 10, height - 20, 0xFFFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    protected final boolean handleMouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (target == EditTarget.MAIN && inside(mouseX, mouseY,
                statusBarX(), statusBarY(), MinimapHud.statusBarWidth(), MinimapHud.statusBarHeight())) {
            draggingStatusBar = true;
            statusDragOffsetX = mouseX - statusBarX();
            statusDragOffsetY = mouseY - statusBarY();
            return true;
        }
        if (!inside(mouseX, mouseY, boxX(), boxY(), boxWidth(), boxHeight())) {
            return false;
        }
        int edge = 9;
        int x = boxX();
        int y = boxY();
        int w = boxWidth();
        int h = boxHeight();
        resizeLeft = mouseX <= x + edge;
        resizeRight = mouseX >= x + w - edge;
        resizeTop = mouseY <= y + edge;
        resizeBottom = mouseY >= y + h - edge;
        if (resizeLeft || resizeRight || resizeTop || resizeBottom) {
            resizing = true;
            resizeOriginalLeft = x;
            resizeOriginalTop = y;
            resizeOriginalRight = x + w;
            resizeOriginalBottom = y + h;
        } else {
            dragging = true;
            dragOffsetX = mouseX - x;
            dragOffsetY = mouseY - y;
        }
        return true;
    }

    protected final boolean handleMouseDragged(
            double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (draggingStatusBar) {
            MinimapConfig config = PlanetEarthMinimapClient.config;
            int barWidth = MinimapHud.statusBarWidth();
            int barHeight = MinimapHud.statusBarHeight();
            config.statusBarX = MathHelper.clamp((int) (mouseX - statusDragOffsetX),
                    0, Math.max(0, width - barWidth));
            config.statusBarY = MathHelper.clamp((int) (mouseY - statusDragOffsetY),
                    0, Math.max(0, height - barHeight));
            return true;
        }
        if (dragging) {
            setBoxX(MathHelper.clamp((int) (mouseX - dragOffsetX), 0, Math.max(0, width - boxWidth())));
            setBoxY(MathHelper.clamp((int) (mouseY - dragOffsetY), 0, Math.max(0, height - boxHeight())));
            return true;
        }
        if (resizing) {
            int minSize = boxMinSize();
            int maxSize = boxMaxSize();
            // Width/height are applied before X/Y on purpose: the overlay box stores its
            // position as an offset from screen centre, so recomputing X/Y has to see the
            // new size first or the box visibly jumps while you drag the left/top edge.
            if (resizeLeft) {
                int newLeft = MathHelper.clamp((int) mouseX,
                        Math.max(0, resizeOriginalRight - maxSize), resizeOriginalRight - minSize);
                setBoxWidth(resizeOriginalRight - newLeft);
                setBoxX(newLeft);
            } else if (resizeRight) {
                int newRight = MathHelper.clamp((int) mouseX,
                        resizeOriginalLeft + minSize, Math.min(width, resizeOriginalLeft + maxSize));
                setBoxWidth(newRight - resizeOriginalLeft);
                setBoxX(resizeOriginalLeft);
            }
            if (resizeTop) {
                int newTop = MathHelper.clamp((int) mouseY,
                        Math.max(0, resizeOriginalBottom - maxSize), resizeOriginalBottom - minSize);
                setBoxHeight(resizeOriginalBottom - newTop);
                setBoxY(newTop);
            } else if (resizeBottom) {
                int newBottom = MathHelper.clamp((int) mouseY,
                        resizeOriginalTop + minSize, Math.min(height, resizeOriginalTop + maxSize));
                setBoxHeight(newBottom - resizeOriginalTop);
                setBoxY(resizeOriginalTop);
            }
            // Only the main box needs an explicit clamp pass: the overlay box's getters
            // already clamp on every read (see overlayX/overlayY/overlayWidth/overlayHeight).
            if (target == EditTarget.MAIN) fitToScreen();
            return true;
        }
        return false;
    }

    protected final void handleMouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        resizing = false;
        resizeLeft = false;
        resizeRight = false;
        resizeTop = false;
        resizeBottom = false;
        draggingStatusBar = false;
    }

    @Override
    public void close() {
        fitToScreen();
        PlanetEarthMinimapClient.config.save();
        playOpenCloseSound();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void fitToScreen() {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        config.width = MathHelper.clamp(config.width, MAIN_MIN_SIZE, Math.min(480, width));
        config.height = MathHelper.clamp(config.height, MAIN_MIN_SIZE, Math.min(480, height));
        config.x = MathHelper.clamp(config.x, 0, Math.max(0, width - config.width));
        config.y = MathHelper.clamp(config.y, 0, Math.max(0, height - config.height));
    }

    // --- Whichever map box (main minimap or overlay map) is currently being edited.
    // The status bar isn't part of this — it has its own independent drag handling
    // above, since it's shown together with the main box rather than swapped for it. ---

    private int boxX() { return target == EditTarget.OVERLAY ? overlayX() : PlanetEarthMinimapClient.config.x; }
    private int boxY() { return target == EditTarget.OVERLAY ? overlayY() : PlanetEarthMinimapClient.config.y; }
    private int boxWidth() {
        return target == EditTarget.OVERLAY ? overlayWidth() : PlanetEarthMinimapClient.config.width;
    }
    private int boxHeight() {
        return target == EditTarget.OVERLAY ? overlayHeight() : PlanetEarthMinimapClient.config.height;
    }
    private int boxMinSize() { return target == EditTarget.OVERLAY ? OVERLAY_MIN_SIZE : MAIN_MIN_SIZE; }
    private int boxMaxSize() { return target == EditTarget.OVERLAY ? OVERLAY_MAX_SIZE : 480; }

    private void setBoxX(int value) {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        if (target == EditTarget.OVERLAY) {
            config.overlayMapOffsetX = value - (width - boxWidth()) / 2;
        } else {
            config.x = value;
        }
    }

    private void setBoxY(int value) {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        if (target == EditTarget.OVERLAY) {
            config.overlayMapOffsetY = value - (height - boxHeight()) / 2;
        } else {
            config.y = value;
        }
    }

    private void setBoxWidth(int value) {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        if (target == EditTarget.OVERLAY) config.overlayMapWidth = value; else config.width = value;
    }

    private void setBoxHeight(int value) {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        if (target == EditTarget.OVERLAY) config.overlayMapHeight = value; else config.height = value;
    }

    /** The overlay map stores its position as an offset from screen centre (so it stays
     *  centred by default on any resolution), so its effective top-left has to be worked
     *  out here rather than read straight from config like the main box's x/y. */
    private int overlayX() {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        int w = overlayWidth();
        return MathHelper.clamp((width - w) / 2 + config.overlayMapOffsetX, 0, Math.max(0, width - w));
    }

    private int overlayY() {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        int h = overlayHeight();
        return MathHelper.clamp((height - h) / 2 + config.overlayMapOffsetY, 0, Math.max(0, height - h));
    }

    private int overlayWidth() {
        return MathHelper.clamp(PlanetEarthMinimapClient.config.overlayMapWidth, OVERLAY_MIN_SIZE, OVERLAY_MAX_SIZE);
    }

    private int overlayHeight() {
        return MathHelper.clamp(PlanetEarthMinimapClient.config.overlayMapHeight, OVERLAY_MIN_SIZE, OVERLAY_MAX_SIZE);
    }

    private int statusBarX() {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        int w = MinimapHud.statusBarWidth();
        return MathHelper.clamp(config.statusBarX, 0, Math.max(0, width - w));
    }

    private int statusBarY() {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        int h = MinimapHud.statusBarHeight();
        return MathHelper.clamp(config.statusBarY, 0, Math.max(0, height - h));
    }

    private boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static float configuredVolume(float baseVolume) {
        return baseVolume * PlanetEarthMinimapClient.config.uiSoundVolumePercent / 100.0f;
    }

    private static void playControlSound(SoundManager soundManager) {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        if (!config.uiSoundsEnabled || config.uiSoundVolumePercent <= 0) return;
        soundManager.play(PlatformCompat.controlSound(2.0f, configuredVolume(0.55f)));
    }

    static void playControlSound() {
        playControlSound(MinecraftClient.getInstance().getSoundManager());
    }

    public static void playOpenCloseSound() {
        MinimapConfig config = PlanetEarthMinimapClient.config;
        if (!config.uiSoundsEnabled || config.uiSoundVolumePercent <= 0) return;
        MinecraftClient.getInstance().getSoundManager().play(
                PlatformCompat.openCloseSound(0.6f, configuredVolume(0.35f)));
    }

    private static final class IntSlider extends SliderWidget {
        private final String label;
        private final String suffix;
        private final int min;
        private final int max;
        private final int step;
        private final IntConsumer setter;

        private IntSlider(int x, int y, int width, String label, String suffix,
                          int min, int max, int step, int current, IntConsumer setter) {
            super(x, y, width, 20, Text.empty(), (current - min) / (double) (max - min));
            this.label = label;
            this.suffix = suffix;
            this.min = min;
            this.max = max;
            this.step = step;
            this.setter = setter;
            updateMessage();
        }

        private int currentValue() {
            int raw = min + (int) Math.round(value * (max - min));
            return MathHelper.clamp(Math.round(raw / (float) step) * step, min, max);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(label + ": " + currentValue() + suffix));
        }

        @Override
        protected void applyValue() {
            int selected = currentValue();
            value = (selected - min) / (double) (max - min);
            setter.accept(selected);
            updateMessage();
        }

        @Override
        public void playDownSound(SoundManager soundManager) {
            playControlSound(soundManager);
        }
    }
}
