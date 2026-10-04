package kr.planetearth.minimap;

import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Shows the web map's Cloudflare check in a real (MCEF) browser so the player can
 *  get past it themselves, exactly as they would on the website. The browser shares
 *  its cookies with the hidden page {@link McefBridge} fetches through, so once this
 *  page loads normally the map starts loading again. Only reachable with MCEF. */
final class WebMapVerifyScreen extends Screen {
    private static final int MARGIN = 24;
    private static final int TOP = 36;
    private static final int BOTTOM = 34;

    private final Screen parent;
    private MCEFBrowser browser;
    private volatile boolean passed;
    private long passedAt;

    WebMapVerifyScreen(Screen parent) {
        super(Text.literal("웹지도 인증"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (browser == null) {
            // A data URL the check sits in front of — once it answers with the
            // actual data instead of the check, the player is through.
            String world = LiveAtlasTileManager.currentDynmapWorld(MinecraftClient.getInstance());
            String url = PlanetEarthMinimapClient.config.mapBaseUrl() + "/up/world/"
                    + (world == null ? "world" : world) + "/0";
            browser = McefBridge.createVerifyBrowser(url, () -> passed = true);
        }
        resizeBrowser();
        addDrawableChild(ButtonWidget.builder(Text.literal("닫기"), pressed -> close())
                .dimensions(width / 2 - 50, height - BOTTOM + 8, 100, 20).build());
    }

    private double scale() {
        return MinecraftClient.getInstance().getWindow().getScaleFactor();
    }

    private int browserWidth() { return width - MARGIN * 2; }

    private int browserHeight() { return height - TOP - BOTTOM; }

    private void resizeBrowser() {
        if (browser == null || browserWidth() <= 10 || browserHeight() <= 10) return;
        browser.resize((int) (browserWidth() * scale()), (int) (browserHeight() * scale()));
    }

    @Override
    public void resize(MinecraftClient client, int width, int height) {
        super.resize(client, width, height);
        resizeBrowser();
    }

    private int toBrowserX(double mouseX) { return (int) ((mouseX - MARGIN) * scale()); }

    private int toBrowserY(double mouseY) { return (int) ((mouseY - TOP) * scale()); }

    private boolean overBrowser(double mouseX, double mouseY) {
        return mouseX >= MARGIN && mouseX < MARGIN + browserWidth()
                && mouseY >= TOP && mouseY < TOP + browserHeight();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        try {
            context.fill(0, 0, width, height, 0xF0101010);
            String status = passed ? "인증 완료! 잠시 후 닫힙니다."
                    : "아래 화면에서 인증을 완료하면 지도가 다시 불러와집니다.";
            context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFFFF);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(status),
                    width / 2, 20, passed ? 0xFF7CFC7C : 0xFFBFBFBF);
            context.fill(MARGIN - 1, TOP - 1, MARGIN + browserWidth() + 1,
                    TOP + browserHeight() + 1, 0xFF8B8B8B);
            if (browser != null) {
                PlatformCompat.drawGlTexture(context, browser.getRenderer().getTextureID(),
                        MARGIN, TOP, browserWidth(), browserHeight());
            }
            super.render(context, mouseX, mouseY, delta);
            if (passed) {
                if (passedAt == 0L) {
                    passedAt = System.currentTimeMillis();
                    WebMapBrowser.onBrowserSuccess();
                } else if (System.currentTimeMillis() - passedAt > 1200L) {
                    close();
                }
            }
        } catch (Throwable error) {
            PlanetEarthMinimapClient.LOGGER.error("웹지도 인증 화면 렌더링 중 오류가 발생했습니다", error);
            close();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (browser != null && overBrowser(mouseX, mouseY)) {
            browser.sendMousePress(toBrowserX(mouseX), toBrowserY(mouseY), button);
            browser.setFocus(true);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (browser != null && overBrowser(mouseX, mouseY)) {
            browser.sendMouseRelease(toBrowserX(mouseX), toBrowserY(mouseY), button);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (browser != null) browser.sendMouseMove(toBrowserX(mouseX), toBrowserY(mouseY));
        super.mouseMoved(mouseX, mouseY);
    }

    // No @Override and both overloads: 1.20.2 added a horizontal scroll parameter,
    // and this lets one source compile on both sides (same as the full map screen).
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return scrollBrowser(mouseX, mouseY, amount);
    }

    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        return scrollBrowser(mouseX, mouseY, verticalAmount);
    }

    private boolean scrollBrowser(double mouseX, double mouseY, double amount) {
        if (browser == null || !overBrowser(mouseX, mouseY)) return false;
        browser.sendMouseWheel(toBrowserX(mouseX), toBrowserY(mouseY), amount, 0);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (browser != null) {
            browser.sendKeyPress(keyCode, scanCode, modifiers);
            browser.setFocus(true);
        }
        return true;
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (browser != null) browser.sendKeyRelease(keyCode, scanCode, modifiers);
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (chr == 0) return false;
        if (browser != null) {
            browser.sendKeyTyped(chr, modifiers);
            browser.setFocus(true);
        }
        return true;
    }

    @Override
    public void close() {
        if (browser != null) {
            McefBridge.closeVerifyBrowser(browser);
            browser = null;
        }
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public void removed() {
        // Leaving through anything other than close() (another screen replacing this
        // one) must not leak a live Chromium browser.
        if (browser != null) {
            McefBridge.closeVerifyBrowser(browser);
            browser = null;
        }
        super.removed();
    }
}
