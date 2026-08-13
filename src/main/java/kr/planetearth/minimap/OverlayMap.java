package kr.planetearth.minimap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/** The "hold G" camera map: a big translucent map centred on the screen that follows
 *  the player and can be zoomed with the mouse wheel while it is held. It is drawn as
 *  a plain HUD overlay (never a Screen), so walking, jumping and looking around all
 *  keep working exactly as they normally would while it's up. */
final class OverlayMap {
    private OverlayMap() {}

    static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        MinimapConfig config = PlanetEarthMinimapClient.config;
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        int width = Math.min(config.overlayMapWidth, Math.max(100, screenWidth - 8));
        int height = Math.min(config.overlayMapHeight, Math.max(100, screenHeight - 8));
        int x = MathHelper.clamp((screenWidth - width) / 2 + config.overlayMapOffsetX,
                0, Math.max(0, screenWidth - width));
        int y = MathHelper.clamp((screenHeight - height) / 2 + config.overlayMapOffsetY,
                0, Math.max(0, screenHeight - height));
        // Opacity was tried several different ways (shader tint, a hand-rolled
        // vertex-coloured draw, and screen-door dithering) and none of them held up, so
        // this is back to a plain solid map — same background colour the small minimap
        // uses.
        MinimapHud.drawMap(context, x, y, width, height, false, config.overlayZoom, 0xE0153427);
    }

    /** Called from the raw GLFW scroll hook in {@link PlanetEarthMinimapClient} —
     *  already gated there on the overlay key being held, so this only ever runs
     *  while the map is actually on screen, and never touches the small minimap's
     *  own independent zoom level. */
    static void handleScroll(double verticalAmount) {
        if (verticalAmount == 0) return;
        MinimapConfig config = PlanetEarthMinimapClient.config;
        config.overlayZoom = MathHelper.clamp(
                config.overlayZoom + (verticalAmount > 0 ? -1 : 1), 0, 7);
    }
}
